package stages

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.epam.edp.stages.impl.ci.impl.codebaseiamgestream.CodebaseImageStreams


@Stage(name = "tests", buildTool = ["gitops"], type = [ProjectType.APPLICATION])
class RunTests  {
    Script script

    void run(context) {
        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
              script.dir("${context.workDir}") {
                script.println("[JENKINS][DEBUG] Mock for stage/tests")
              }
            }
        }
    }
}
return RunTests
