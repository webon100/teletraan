/**
 * Copyright 2016 Pinterest, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *    
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.deployservice.handler;

import com.pinterest.deployservice.ServiceContext;
import com.pinterest.deployservice.bean.AcceptanceStatus;
import com.pinterest.deployservice.bean.BuildBean;
import com.pinterest.deployservice.bean.CommitBean;
import com.pinterest.deployservice.bean.DeployBean;
import com.pinterest.deployservice.bean.DeployFilterBean;
import com.pinterest.deployservice.bean.DeployQueryResultBean;
import com.pinterest.deployservice.bean.DeployState;
import com.pinterest.deployservice.bean.DeployType;
import com.pinterest.deployservice.bean.EnvWebHookBean;
import com.pinterest.deployservice.bean.EnvironBean;
import com.pinterest.deployservice.bean.PromoteBean;
import com.pinterest.deployservice.bean.PromoteDisablePolicy;
import com.pinterest.deployservice.bean.PromoteType;
import com.pinterest.deployservice.bean.UpdateStatement;
import com.pinterest.deployservice.common.CommonUtils;
import com.pinterest.deployservice.common.Constants;
import com.pinterest.deployservice.common.DeployInternalException;
import com.pinterest.deployservice.common.HTTPClient;
import com.pinterest.deployservice.common.StateMachines;
import com.pinterest.deployservice.common.WebhookDataFactory;
import com.pinterest.deployservice.dao.BuildDAO;
import com.pinterest.deployservice.dao.DeployDAO;
import com.pinterest.deployservice.dao.EnvironDAO;
import com.pinterest.deployservice.dao.PromoteDAO;
import com.pinterest.deployservice.db.DatabaseUtil;
import com.pinterest.deployservice.db.DeployQueryFilter;
import com.pinterest.deployservice.scm.SourceControlManager;

import com.google.common.base.Joiner;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

public class DeployHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DeployHandler.class);
    private static final String FEED_TEMPLATE = "{\"type\":\"Deploy\",\"environment\":\"%s (%s)\",\"description\":\"http://deploy.pinadmin.com/deploy/%s\","
        + "\"author\":\"%s\",\"automation\":\"%s\",\"source\":\"Teletraan\",\"optional-1\":\"%s\",\"optional-2\":\"\"}";
    private static final String COMPARE_DEPLOY_URL = "https://deploy.pinadmin.com/env/%s/%s/compare_deploys_2/?chkbox_1=%s&chkbox_2=%s";

    private DeployDAO deployDAO;
    private EnvironDAO environDAO;
    private BuildDAO buildDAO;
    private PromoteDAO promoteDAO;
    private BasicDataSource dataSource;
    private CommonHandler commonHandler;
    private DataHandler dataHandler;
    private SourceControlManager sourceControlManager;
    private ExecutorService jobPool;
    private String deployBoardUrlPrefix;
    private String changeFeedUrl;

    private final class NotifyJob implements Callable<Void> {
        private EnvironBean envBean;
        private DeployBean newDeployBean;
        private DeployBean oldDeployBean;
        private HTTPClient httpClient;
        private CommonHandler commonHandler;
        private String deployBoardUrlPrefix;
        private String changeFeedUrl;
        private final int RETRIES = 3;

        public NotifyJob(EnvironBean envBean, DeployBean newDeployBean,
            DeployBean oldDeployBean, CommonHandler commonHandler,
            String deployBoardUrlPrefix, String changeFeedUrl) {
            this.envBean = envBean;
            this.newDeployBean = newDeployBean;
            this.oldDeployBean = oldDeployBean;
            this.httpClient = new HTTPClient();
            this.commonHandler = commonHandler;
            this.deployBoardUrlPrefix = deployBoardUrlPrefix;
            this.changeFeedUrl = changeFeedUrl;
        }

        private void updateChangeFeed() {
            try {
                String autoPromote = "False";
                if (newDeployBean.getOperator().equals(Constants.AUTO_PROMOTER_NAME))
                    autoPromote = "True";
                String feedPayload = String.format(FEED_TEMPLATE, envBean.getEnv_name(), envBean.getStage_name(), newDeployBean.getDeploy_id(),
                    newDeployBean.getOperator(), autoPromote, newDeployBean.getDeploy_type());
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                httpClient.post(changeFeedUrl, feedPayload, headers, RETRIES);
                LOG.info("Send change feed {} to {} successfully", feedPayload, changeFeedUrl);
            } catch (Exception e) {
                LOG.error("Failed to send deploy info to Change Feed for deploy {}", newDeployBean.getDeploy_id());
            }
        }

        void sendStartDeployMessage(String additionalMessage) {
            try {
                String operator = newDeployBean.getOperator();
                String buildId = newDeployBean.getBuild_id();
                DeployType deployType = newDeployBean.getDeploy_type();
                BuildBean buildBean = buildDAO.getById(buildId);
                String WebLink = deployBoardUrlPrefix + String.format("/env/%s/%s/deploy/",
                    envBean.getEnv_name(),
                    envBean.getStage_name());

                String action = commonHandler.getDeployAction(deployType);
                String message = String.format("%s/%s: %s %s/%s started. See details %s.",
                    envBean.getEnv_name(),
                    envBean.getStage_name(),
                    action,
                    buildBean.getScm_branch(),
                    buildBean.getScm_commit_7(),
                    WebLink);

                commonHandler.sendChatMessage(operator, envBean.getChatroom(), message, "yellow");
                if (StringUtils.isNotEmpty(additionalMessage)) {
                    LOG.debug(String.format("Sending additional message: %s to chat", additionalMessage));
                    commonHandler.sendChatMessage(operator, envBean.getChatroom(), additionalMessage, "yellow");
                }
            } catch (Exception e) {
                LOG.error("Failed to send start notification!", e);
            }
        }

        public Void call() {
            String additonalMessage = generateMentions(envBean, newDeployBean, oldDeployBean);
            sendStartDeployMessage(additonalMessage);
            LOG.info("Successfully send deploy start message for deploy {}", newDeployBean.getDeploy_id());

            if (!StringUtils.isEmpty(changeFeedUrl)) {
                updateChangeFeed();
                LOG.info("Successfully send deploy info to Change Feed for deploy {}", newDeployBean.getDeploy_id());
            }

            return null;
        }
    }

    public DeployHandler(ServiceContext serviceContext) {
        deployDAO = serviceContext.getDeployDAO();
        environDAO = serviceContext.getEnvironDAO();
        buildDAO = serviceContext.getBuildDAO();
        promoteDAO = serviceContext.getPromoteDAO();
        dataSource = serviceContext.getDataSource();
        commonHandler = new CommonHandler(serviceContext);
        dataHandler = new DataHandler(serviceContext);
        sourceControlManager = serviceContext.getSourceControlManager();
        jobPool = serviceContext.getJobPool();
        deployBoardUrlPrefix = serviceContext.getDeployBoardUrlPrefix();
        changeFeedUrl = serviceContext.getChangeFeedUrl();
    }

    private String generateMentions(EnvironBean envBean, DeployBean newDeployBean, DeployBean oldDeployBean) {
        DeployType deployType = newDeployBean.getDeploy_type();
        if (deployType == DeployType.RESTART) {
            // It does not make sense to mentioning author for rollback and restart
            return null;
        }

        if (!envBean.getNotify_authors()) {
            return null;
        }

        String newBuildId = newDeployBean.getBuild_id();
        String oldBuildId = oldDeployBean.getBuild_id();
        if (deployType == DeployType.ROLLBACK) {
            // Reverse the old and new for rollbacks
            newBuildId = oldDeployBean.getBuild_id();
            oldBuildId = newDeployBean.getBuild_id();
        }

        try {
            BuildBean newBuildBean = buildDAO.getById(newBuildId);
            BuildBean oldBuildBean = buildDAO.getById(oldBuildId);

            // Get all the commits between old and new deploy
            List<CommitBean> commits = sourceControlManager.getCommits(newBuildBean.getScm_repo(),
                newBuildBean.getScm_commit(), oldBuildBean.getScm_commit(), 0);

            Set<String> authors = new HashSet<>();
            for (CommitBean commit : commits) {
                if (commit.getAuthor().toLowerCase().equals("unknown")) {
                    // ignore unknown authors
                    continue;
                }
                // TODO hipchat is different, screw it for now
                authors.add(String.format("<@%s>", commit.getAuthor()));
            }

            String mentions = Joiner.on(",").join(authors);
            String compareUrl = String.format(COMPARE_DEPLOY_URL, envBean.getEnv_name(),
                envBean.getStage_name(), newBuildId, oldBuildId);

            if (deployType.equals(DeployType.ROLLBACK)) {
                return String.format("This rollback reverses %d commits from the following pingineers: %s. See changes <%s|here>",
                    commits.size(), mentions, compareUrl);
            } else {
                return String.format("This deploy features %d commits from the following pingineers: %s. See changes <%s|here>",
                    commits.size(), mentions, compareUrl);
            }
        } catch (Exception e) {
            LOG.error("Failed to generate author notification message", e);
            return null;
        }
    }

    String internalDeploy(EnvironBean envBean, DeployBean deployBean) throws Exception {
        // TODO deploy becomes longer process, consider to have worker to do this step
        String deployId = CommonUtils.getBase64UUID();
        deployBean.setDeploy_id(deployId);
        deployBean.setAcc_status(Constants.DEFAULT_ACCEPTANCE_STATUS);
        long now = System.currentTimeMillis();
        deployBean.setLast_update(now);

        deployBean.setState(DeployState.RUNNING);
        deployBean.setStart_date(now);
        long total = environDAO.countTotalCapacity(envBean.getEnv_id(), envBean.getEnv_name(), envBean.getStage_name());
        deployBean.setSuc_total(0);
        deployBean.setFail_total(0);
        deployBean.setTotal((int) total);

        // Do a transactional update for everything need to
        List<UpdateStatement> statements = new ArrayList<>();
        statements.add(deployDAO.genInsertStatement(deployBean));

        EnvironBean updateEnvBean = new EnvironBean();
        updateEnvBean.setDeploy_id(deployId);
        updateEnvBean.setDeploy_type(deployBean.getDeploy_type());

        statements.add(environDAO.genUpdateStatement(envBean.getEnv_id(), updateEnvBean));

        // Deprecate/Obsolete the previous deploy
        DeployBean oldDeployBean = null;
        String oldDeployId = envBean.getDeploy_id();
        DeployState finalState = null;
        if (oldDeployId != null) {
            oldDeployBean = deployDAO.getById(oldDeployId);

            finalState = StateMachines.FINAL_STATE_TRANSITION_MAP.get(oldDeployBean.getState());
            DeployBean updatedDeployBean = new DeployBean();
            updatedDeployBean.setState(finalState);

            if (!StateMachines.FINAL_ACCEPTANCE_STATUSES.contains(oldDeployBean.getAcc_status())) {
                updatedDeployBean.setAcc_status(AcceptanceStatus.TERMINATED);
            }

            updatedDeployBean.setLast_update(System.currentTimeMillis());
            statements.add(deployDAO.genUpdateStatement(oldDeployId, updatedDeployBean));
        }

        LOG.debug("Create and persist deploy {} ", deployBean);
        DatabaseUtil.transactionalUpdate(dataSource, statements);
        LOG.info("Announce new deploy {} for env {} and retire older deploy {} to state {}",
            deployId, envBean.getEnv_id(), oldDeployId, finalState);

        jobPool.submit(new NotifyJob(envBean, deployBean, oldDeployBean,
            commonHandler, deployBoardUrlPrefix, changeFeedUrl));

        // Submit pre-deploy Webhook, if exists
        EnvWebHookBean webhook = dataHandler.getDataById(envBean.getWebhooks_config_id(), WebhookDataFactory.class);
        if (webhook != null && !CollectionUtils.isEmpty(webhook.getPreDeployHooks())) {
            jobPool.submit(new WebhookJob(webhook.getPreDeployHooks(), deployBean, envBean));
        }

        LOG.info("Submitted notify job for deploy {}", deployId);

        return deployId;
    }

    public DeployBean getDeploySafely(String deployId) throws Exception {
        if (deployId == null) {
            throw new DeployInternalException("No deploy exists yet.");
        }
        DeployBean deployBean = deployDAO.getById(deployId);
        if (deployBean == null) {
            throw new DeployInternalException("Deploy %s does not exist.", deployId);
        }
        return deployBean;
    }

    // Disable the Auto Promote if this is user behavior, and DisablePolicy agrees
    public void disableAutoPromote(EnvironBean envBean, String operator, boolean force) {
        try {
            if (!force && operator.equals(Constants.AUTO_PROMOTER_NAME)) {
                // Do not disable if this is auto deploy
                return;
            }

            PromoteBean promoteBean = promoteDAO.getById(envBean.getEnv_id());
            if (promoteBean == null || promoteBean.getType() == PromoteType.MANUAL) {
                // No need to update
                return;
            }

            if (!force && promoteBean.getDisable_policy() == PromoteDisablePolicy.MANUAL) {
                // Disable policy does not allow disable ( has to manually do it )
                return;
            }

            // Otherwise, disable auto promote
            PromoteBean updateBean = new PromoteBean();
            updateBean.setType(PromoteType.MANUAL);
            updateBean.setLast_operator(operator);
            updateBean.setLast_update(System.currentTimeMillis());
            promoteDAO.update(envBean.getEnv_id(), updateBean);
            LOG.info("Disable auto promote for env {} due to the manual deploy by {}", envBean, operator);
        } catch (Exception e) {
            LOG.error("Failed to disable auto promote env {}", envBean, e);
        }
    }

    public String deploy(EnvironBean envBean, String buildId, String desc, String operator) throws Exception {
        DeployBean deployBean = new DeployBean();
        deployBean.setEnv_id(envBean.getEnv_id());
        deployBean.setBuild_id(buildId);
        deployBean.setDescription(desc);
        deployBean.setDeploy_type(DeployType.REGULAR);
        deployBean.setOperator(operator);

        disableAutoPromote(envBean, operator, false);

        return internalDeploy(envBean, deployBean);
    }

    public void updateAcceptanceStatus(String deployId, AcceptanceStatus newStatus, String operator) throws Exception {
        getDeploySafely(deployId);
        DeployBean updateBean = new DeployBean();
        updateBean.setAcc_status(newStatus);
        updateBean.setLast_update(System.currentTimeMillis());
        // TODO use oldStatus to do atomic update status or state
        deployDAO.update(deployId, updateBean);
    }

    public String promote(EnvironBean envBean, String fromDeployId, String description, String operator) throws Exception {
        DeployBean fromDeployBean = getDeploySafely(fromDeployId);

        DeployBean deployBean = new DeployBean();
        deployBean.setEnv_id(envBean.getEnv_id());
        deployBean.setBuild_id(fromDeployBean.getBuild_id());
        deployBean.setDescription(description);
        deployBean.setDeploy_type(DeployType.REGULAR);
        deployBean.setOperator(operator);
        deployBean.setFrom_deploy(fromDeployId);

        disableAutoPromote(envBean, operator, false);

        return internalDeploy(envBean, deployBean);
    }

    DeployBean getLastSucceededDeploy(EnvironBean envBean) throws Exception {
        int index = 1;
        int size = 10;
        DeployFilterBean filterBean = new DeployFilterBean();
        filterBean.setEnvIds(Arrays.asList(envBean.getEnv_id()));
        filterBean.setPageIndex(index);
        filterBean.setPageSize(size);
        while (true) {
            DeployQueryFilter filter = new DeployQueryFilter(filterBean);
            DeployQueryResultBean resultBean = deployDAO.getAllDeploys(filter);
            if (resultBean.getTotal() < 1) {
                LOG.warn("Could not find any previous succeeded deploy in env {}", envBean.getEnv_id());
                return null;
            }
            for (DeployBean deploy : resultBean.getDeploys()) {
                if (deploy.getState() == DeployState.SUCCEEDED) {
                    return deploy;
                }
            }
            index += 1;
        }
    }

    public String rollback(EnvironBean envBean, String toDeployId, String description, String operator) throws Exception {
        DeployBean toDeployBean;
        if (toDeployId == null) {
            toDeployBean = getLastSucceededDeploy(envBean);
            if (toDeployBean == null) {
                throw new DeployInternalException(String.format("Could not find last succeeded deploy for env %s/%s",
                    envBean.getEnv_name(), envBean.getStage_name()));
            }
        } else {
            toDeployBean = getDeploySafely(toDeployId);
        }

        DeployBean deployBean = new DeployBean();
        deployBean.setEnv_id(envBean.getEnv_id());
        deployBean.setBuild_id(toDeployBean.getBuild_id());
        deployBean.setDescription(description);
        deployBean.setDeploy_type(DeployType.ROLLBACK);
        deployBean.setOperator(operator);
        deployBean.setAlias(toDeployId);

        disableAutoPromote(envBean, operator, false);

        return internalDeploy(envBean, deployBean);
    }

    public String restart(EnvironBean envBean, String description, String operator) throws Exception {
        DeployBean prevDeployBean = getDeploySafely(envBean.getDeploy_id());
        DeployBean deployBean = new DeployBean();
        deployBean.setEnv_id(envBean.getEnv_id());
        deployBean.setBuild_id(prevDeployBean.getBuild_id());
        deployBean.setDescription(description);
        deployBean.setDeploy_type(DeployType.RESTART);
        deployBean.setOperator(operator);

        disableAutoPromote(envBean, operator, false);

        return internalDeploy(envBean, deployBean);
    }
}
