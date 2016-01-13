# Copyright 2016 Pinterest, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#  
#     http://www.apache.org/licenses/LICENSE-2.0
#    
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# -*- coding: utf-8 -*-
"""Collection of all environs related calls
"""
from deploy_board.webapp.helpers.deployclient import DeployClient

DEFAULT_ENV_SIZE = 30
BUILD_STAGE = 'BUILD'

DEPLOY_STAGE_VALUES = ['UNKNOWN', 'PRE_DOWNLOAD', 'DOWNLOADING', 'POST_DOWNLOAD', 'STAGING',
                       'PRE_RESTART', 'RESTARTING', 'POST_RESTART', 'SERVING_BUILD', 'STOPPING', 'STOPPED']

DEPLOY_PRIORITY_VALUES = ['NORMAL', 'HIGH', 'LOW', 'HIGHER', 'LOWER']

ACCEPTANCE_TYPE_VALUES = ['AUTO', 'MANUAL']

ACCEPTANCE_STATUS_VALUES = ['PENDING_DEPLOY', 'OUTSTANDING', 'PENDING_ACCEPT', 'ACCEPTED',
                            'REJECTED',
                            'TERMINATED']

AGENT_STATE_VALUES = ["NORMAL", "PAUSED_BY_SYSTEM", "PAUSED_BY_USER", "RESET", "DELETE",
                      "UNREACHABLE", "STOP"]

AGENT_STATUS_VALUES = ["SUCCEEDED", "UNKNOWN", "AGENT_FAILED", "RETRYABLE_AGENT_FAILED",
                       "SCRIPT_FAILED", "ABORTED_BY_SERVICE", "SCRIPT_TIMEOUT", "TOO_MANY_RETRY",
                       "RUNTIME_MISMATCH"]

PROMOTE_TYPE_VALUES = ['MANUAL', 'AUTO']

PROMOTE_FAILED_POLICY_VALUES = ['CONTINUE', 'DISABLE', 'ROLLBACK']

PROMOTE_DISABLE_POLICY_VALUES = ['MANUAL', 'AUTO']

deployclient = DeployClient()


def get_all_env_names(request, name_filter=None, name_only=True, index=1, size=DEFAULT_ENV_SIZE):
    params = [('pageIndex', index), ('pageSize', size)]
    if name_filter:
        params.append(('nameFilter', name_filter))
    return deployclient.get("/envs/names", request.teletraan_user_id.token, params=params)


def get_all_env_stages(request, env_name):
    return deployclient.get("/envs", request.teletraan_user_id.token,
                            params=[("envName", env_name)])


def get_all_envs_by_group(request, group_name):
    params = [('groupName', group_name)]
    return deployclient.get("/envs/", request.teletraan_user_id.token, params=params)


def get(request, id):
    return deployclient.get("/envs/%s" % id, request.teletraan_user_id.token)


def get_env_by_stage(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s" % (env_name, stage_name), request.teletraan_user_id.token)


def get_env_capacity(request, env_name, stage_name, capacity_type=None):
    params = []
    if capacity_type:
        params.append(("capacityType", capacity_type))
    return deployclient.get("/envs/%s/%s/capacity" % (env_name, stage_name),
                            request.teletraan_user_id.token, params=params)


def update_env_capacity(request, env_name, stage_name, capacity_type=None, data=None):
    params = []
    if capacity_type:
        params.append(("capacityType", capacity_type))
    return deployclient.put("/envs/%s/%s/capacity" % (env_name, stage_name),
                            request.teletraan_user_id.token, params=params, data=data)


def create_env(request, data):
    return deployclient.post("/envs", request.teletraan_user_id.token, data=data)


def update_env_basic_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s" % (env_name, stage_name), request.teletraan_user_id.token,
                            data=data)


def get_env_script_config(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s/script_configs" % (env_name, stage_name),
                            request.teletraan_user_id.token)


def update_env_script_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s/script_configs" % (env_name, stage_name),
                            request.teletraan_user_id.token, data=data)


def get_env_agent_config(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s/agent_configs" % (env_name, stage_name),
                            request.teletraan_user_id.token)


def update_env_agent_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s/agent_configs" % (env_name, stage_name),
                            request.teletraan_user_id.token, data=data)


def get_env_alarms_config(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s/alarms" % (env_name, stage_name),
                            request.teletraan_user_id.token)


def update_env_alarms_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s/alarms" % (env_name, stage_name),
                            request.teletraan_user_id.token, data=data)


def get_env_metrics_config(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s/metrics" % (env_name, stage_name),
                            request.teletraan_user_id.token)


def update_env_metrics_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s/metrics" % (env_name, stage_name),
                            request.teletraan_user_id.token, data=data)


def get_env_hooks_config(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s/web_hooks" % (env_name, stage_name),
                            request.teletraan_user_id.token)


def update_env_hooks_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s/web_hooks" % (env_name, stage_name),
                            request.teletraan_user_id.token, data=data)


def get_env_promotes_config(request, env_name, stage_name):
    return deployclient.get("/envs/%s/%s/promotes" % (env_name, stage_name),
                            request.teletraan_user_id.token)


def update_env_promotes_config(request, env_name, stage_name, data):
    return deployclient.put("/envs/%s/%s/promotes" % (env_name, stage_name),
                            request.teletraan_user_id.token, data=data)


def delete_env(request, env_name, stage_name):
    return deployclient.delete("/envs/%s/%s" % (env_name, stage_name),
                               request.teletraan_user_id.token)


def get_config_history(request, env_name, stage_name, index, size):
    params = [('pageIndex', index), ('pageSize', size)]
    return deployclient.get("/envs/%s/%s/history" % (env_name, stage_name),
                            request.teletraan_user_id.token, params=params)