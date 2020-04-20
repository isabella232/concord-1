package com.walmartlabs.concord.plugins.crypto;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2020 Walmart Inc.
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

import com.walmartlabs.concord.runtime.v2.model.ProcessConfiguration;
import com.walmartlabs.concord.runtime.v2.model.ProjectInfo;
import com.walmartlabs.concord.runtime.v2.sdk.SecretService;
import com.walmartlabs.concord.runtime.v2.sdk.SecretService.KeyPair;
import com.walmartlabs.concord.runtime.v2.sdk.SecretService.UsernamePassword;
import com.walmartlabs.concord.runtime.v2.sdk.Task;
import com.walmartlabs.concord.runtime.v2.sdk.TaskContext;
import com.walmartlabs.concord.runtime.v2.sdk.WorkingDirectory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Named("crypto")
public class CryptoTaskV2 implements Task {

    private final SecretService secretService;
    private final WorkingDirectory workDir;
    private final ProcessConfiguration processCfg;

    @Inject
    public CryptoTaskV2(SecretService secretService,
                        WorkingDirectory workDir,
                        ProcessConfiguration processCfg) {

        this.secretService = secretService;
        this.workDir = workDir;
        this.processCfg = processCfg;
    }

    public String exportAsString(String orgName, String name, String password) throws Exception {
        return secretService.exportAsString(orgName, name, password);
    }

    public Map<String, String> exportKeyAsFile(String orgName, String name, String password) throws Exception {
        KeyPair keyPair = secretService.exportKeyAsFile(orgName, name, password);

        Path baseDir = workDir.getValue();

        Map<String, String> m = new HashMap<>();
        m.put("private", baseDir.relativize(keyPair.privateKey()).toString());
        m.put("public", baseDir.relativize(keyPair.publicKey()).toString());
        return m;
    }

    public Map<String, String> exportCredentials(String orgName, String name, String password) throws Exception {
        UsernamePassword credentials = secretService.exportCredentials(orgName, name, password);

        Map<String, String> m = new HashMap<>();
        m.put("username", credentials.username());
        m.put("password", credentials.password());
        return m;
    }

    public String exportAsFile(String orgName, String name, String password) throws Exception {
        Path path = secretService.exportAsFile(orgName, name, password);
        Path baseDir = workDir.getValue();
        return baseDir.relativize(path).toString();
    }

    public String encryptString(String value) throws Exception {
        ProjectInfo projectInfo = processCfg.projectInfo();

        String orgName = projectInfo.orgName();
        String projectName = projectInfo.projectName();
        if (orgName == null || projectName == null) {
            throw new IllegalStateException("The process must run in a project in order to be able to encrypt strings.");
        }

        return secretService.encryptString(orgName, projectName, value);
    }

    public String decryptString(String s) throws Exception {
        return secretService.decryptString(s);
    }

    @Override
    public Serializable execute(TaskContext ctx) {
        throw new IllegalStateException("The task doesn't support full task syntax yet. " +
                "Please call the task using expressions.");
    }
}
