import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "restore-registry-bucket", buildTool = ["gitops"], type = [ProjectType.REGISTRY])
class RestoreBackup  {
    Script script

    void run(context) {
        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
                    try {
                        script.env.NAMESPACE = context.codebase.config.name
                        script.env.edpName = context.job.edpName
                        script.println("${script.env.NAMESPACE}")

                        def RESULT = script.sh(script: """ oc get regbackup -o json | jq -c '.items[].spec | select(."registry-alias"=="${script.env.NAMESPACE}")."velero-backup-name"' | cut -c2- |rev | cut -c2- | rev """, returnStdout: true).trim()
                        def userInput = script.input(id: 'userInput', message: 'Please select backup to restore', parameters: [
                                [$class: 'ChoiceParameterDefinition', choices: RESULT, description: 'List of backups', name: 'input'],
                        ])

                        def backuptorestore = userInput
                        script.sh "sh /home/jenkins/restore/restore_rclone.sh ${script.env.NAMESPACE} ${script.env.edpName} ${backuptorestore}"
                        script.sh "echo ${backuptorestore}"
                    }
                    catch (e) {
                        script.println e
                        currentBuild.result = 'FAILURE'
                    }
                    finally {
                        script.println("Restore Complete")
                    }

                }
            }
        }
    }
}
return RestoreBackup
