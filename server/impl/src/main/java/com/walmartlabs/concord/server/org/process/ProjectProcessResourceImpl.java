package com.walmartlabs.concord.server.org.process;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.common.ConfigurationUtils;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.server.api.org.process.ProjectProcessResource;
import com.walmartlabs.concord.server.api.process.FormListEntry;
import com.walmartlabs.concord.server.api.process.ProcessEntry;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.console.CustomFormService;
import com.walmartlabs.concord.server.console.FormSessionResponse;
import com.walmartlabs.concord.server.console.ResponseTemplates;
import com.walmartlabs.concord.server.org.OrganizationDao;
import com.walmartlabs.concord.server.org.project.ProjectDao;
import com.walmartlabs.concord.server.org.project.RepositoryDao;
import com.walmartlabs.concord.server.process.ConcordFormService;
import com.walmartlabs.concord.server.process.Payload;
import com.walmartlabs.concord.server.process.PayloadBuilder;
import com.walmartlabs.concord.server.process.ProcessManager;
import com.walmartlabs.concord.server.process.pipelines.processors.RequestInfoProcessor;
import com.walmartlabs.concord.server.process.queue.ProcessQueueDao;
import com.walmartlabs.concord.server.security.UserPrincipal;
import io.takari.bpm.api.ExecutionException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.siesta.Resource;
import org.sonatype.siesta.Validate;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.*;

import static javax.ws.rs.core.Response.Status;

@Named
public class ProjectProcessResourceImpl implements ProjectProcessResource, Resource {

    private static final Logger log = LoggerFactory.getLogger(ProjectProcessResourceImpl.class);

    private static final long STATUS_REFRESH_DELAY = 250;

    private final ProcessManager processManager;
    private final OrganizationDao orgDao;
    private final ProcessQueueDao queueDao;
    private final ConcordFormService formService;
    private final CustomFormService customFormService;
    private final ResponseTemplates responseTemplates;
    private final ProjectDao projectDao;
    private final RepositoryDao repositoryDao;

    @Inject
    public ProjectProcessResourceImpl(ProcessManager processManager,
                                      OrganizationDao orgDao,
                                      ProcessQueueDao queueDao,
                                      ConcordFormService formService,
                                      CustomFormService customFormService,
                                      ProjectDao projectDao, RepositoryDao repositoryDao) {

        this.processManager = processManager;
        this.orgDao = orgDao;
        this.queueDao = queueDao;
        this.formService = formService;
        this.customFormService = customFormService;
        this.projectDao = projectDao;
        this.repositoryDao = repositoryDao;
        this.responseTemplates = new ResponseTemplates();
    }

    @Override
    @Validate
    @RequiresAuthentication
    public Response start(String orgName, String projectName, String repoName,
                          String entryPoint, String activeProfiles, UriInfo uriInfo) {
        try {
            return doStartProcess(orgName, projectName, repoName, entryPoint, activeProfiles, uriInfo);
        } catch (Exception e) {
            log.error("startProcess ['{}', '{}', '{}', '{}', '{}'] -> error",
                    orgName, projectName, repoName, entryPoint, activeProfiles, e);
            return processError(null, "Process error: " + e.getMessage());
        }
    }

    private Response doStartProcess(String orgName, String projectName, String repoName, String entryPoint, String activeProfiles, UriInfo uriInfo) {
        Map<String, Object> req = new HashMap<>();
        if (activeProfiles != null) {
            String[] as = activeProfiles.split(",");
            req.put(InternalConstants.Request.ACTIVE_PROFILES_KEY, Arrays.asList(as));
        }

        if (uriInfo != null) {
            Map<String, Object> args = new HashMap<>();
            args.put("requestInfo", RequestInfoProcessor.createRequestInfo(uriInfo));
            args.putAll(parseArguments(uriInfo));
            req.put(InternalConstants.Request.ARGUMENTS_KEY, args);
        }

        UUID instanceId = UUID.randomUUID();

        try {
            UUID orgId = getOrgId(orgName);
            UUID projectId = getProjectId(orgId, projectName);
            UUID repoId = getRepoId(projectId, repoName);

            Payload payload = new PayloadBuilder(instanceId)
                    .organization(orgId)
                    .project(projectId)
                    .repository(repoId)
                    .entryPoint(entryPoint)
                    .initiator(getInitiator())
                    .configuration(req)
                    .build();

            processManager.start(payload, false);
        } catch (Exception e) {
            return processError(instanceId, e.getMessage());
        }

        while (true) {
            ProcessEntry psr = getProcess(instanceId);
            ProcessStatus status = psr.getStatus();

            if (status == ProcessStatus.SUSPENDED) {
                break;
            } else if (status == ProcessStatus.FAILED || status == ProcessStatus.CANCELLED) {
                return processError(instanceId, "Process failed");
            } else if (status == ProcessStatus.FINISHED) {
                return processFinished(instanceId);
            }

            try {
                // TODO exp back off?
                Thread.sleep(STATUS_REFRESH_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        List<FormListEntry> forms;
        try {
            forms = formService.list(instanceId);
        } catch (ExecutionException e) {
            return processError(instanceId, "Error while retrieving the list of process forms");
        }

        if (forms == null || forms.isEmpty()) {
            return processError(instanceId, "Invalid process state: no forms found");
        }

        FormListEntry f = forms.get(0);
        if (!f.isCustom()) {
            String dst = "/#/process/" + instanceId + "/form/" + f.getFormInstanceId() + "?fullScreen=true&wizard=true";
            return Response.status(Status.MOVED_PERMANENTLY)
                    .header(HttpHeaders.LOCATION, dst)
                    .build();
        }

        FormSessionResponse fsr = customFormService.startSession(instanceId, f.getFormInstanceId());
        return Response.status(Status.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, fsr.getUri())
                .build();
    }

    private static Map<String, Object> parseArguments(UriInfo uriInfo) {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, List<String>> e : uriInfo.getQueryParameters(true).entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("arguments.")) {
                continue;
            }
            k = k.substring("arguments.".length());

            Object v = e.getValue();
            if (e.getValue().size() == 1) {
                v = e.getValue().get(0);
            }

            Map<String, Object> m = ConfigurationUtils.toNested(k, v);
            result = ConfigurationUtils.deepMerge(result, m);
        }

        return result;
    }

    private UUID getOrgId(String orgName) {
        UUID id = orgDao.getId(orgName);
        if (id == null) {
            throw new ValidationErrorsException("Organization not found: " + orgName);
        }
        return id;
    }

    private UUID getProjectId(UUID orgId, String projectName) {
        if (projectName == null) {
            return null;
        }

        if (orgId == null) {
            throw new ValidationErrorsException("Organization name is required");
        }

        UUID id = projectDao.getId(orgId, projectName);
        if (id == null) {
            throw new ValidationErrorsException("Project not found: " + projectName);
        }
        return id;
    }

    private UUID getRepoId(UUID projectId, String repoName) {
        if (repoName == null) {
            return null;
        }

        if (projectId == null) {
            throw new ValidationErrorsException("Project name is required");
        }

        UUID id = repositoryDao.getId(projectId, repoName);
        if (id == null) {
            throw new ValidationErrorsException("Repository not found: " + repoName);
        }
        return id;
    }

    private ProcessEntry getProcess(UUID instanceId) {
        ProcessEntry e = queueDao.get(instanceId);
        if (e == null) {
            log.warn("getProcess ['{}'] -> not found", instanceId);
            throw new WebApplicationException("Process instance not found", Status.NOT_FOUND);
        }
        return e;
    }

    private Response processFinished(UUID instanceId) {
        return responseTemplates.processFinished(Response.ok(),
                Collections.singletonMap("instanceId", instanceId))
                .build();
    }

    private Response processError(UUID instanceId, String message) {
        Map<String, Object> args = new HashMap<>();
        if (instanceId != null) {
            args.put("instanceId", instanceId);
        }
        args.put("message", message);

        return responseTemplates.processError(Response.status(Status.INTERNAL_SERVER_ERROR), args)
                .build();
    }

    private static String getInitiator() {
        Subject subject = SecurityUtils.getSubject();
        if (subject == null || !subject.isAuthenticated()) {
            return null;
        }

        UserPrincipal p = (UserPrincipal) subject.getPrincipal();
        return p.getUsername();
    }
}
