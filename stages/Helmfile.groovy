package stages

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "deploy-openshift", buildTool = ["gitops"], type = [ProjectType.APPLICATION])
class Helmfile  {
    Script script

    void run(context) {

    }
}
return Helmfile
