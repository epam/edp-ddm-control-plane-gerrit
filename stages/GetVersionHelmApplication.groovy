/* Copyright 2019 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

package com.epam.edp.stages.impl.ci.impl.getversion


import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "get-version", buildTool = ["gitops"], type = [ProjectType.APPLICATION])
class GetVersionHelmApplication {
    Script script

    def setVersionToArtifact(context) {
        script.sh """
             set -eo pipefail
             kubectl patch codebasebranches.v2.edp.epam.com ${context.codebase.config.name}-${context.git.branch.replaceAll(/\//, "-")} --type=merge -p '{\"status\": {\"build\": "${context.codebase.currentBuildNumber}"}}'
        """
    }

    void run(context) {
        setVersionToArtifact(context)
        context.codebase.vcsTag = "build/${context.codebase.version}"
        context.codebase.isTag = "${context.codebase.version}"
        context.codebase.deployableModuleDir = "${context.workDir}"

        script.println("[JENKINS][DEBUG] Artifact version - ${context.codebase.version}")
        script.println("[JENKINS][DEBUG] VCS tag - ${context.codebase.vcsTag}")
        script.println("[JENKINS][DEBUG] IS tag - ${context.codebase.isTag}")
    }
}

return GetVersionHelmApplication