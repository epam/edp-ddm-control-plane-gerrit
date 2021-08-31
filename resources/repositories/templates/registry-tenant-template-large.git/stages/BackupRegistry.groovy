import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "create-backup", buildTool = ["gitops"], type = [ProjectType.REGISTRY])
class CreateBackup  {
    Script script

    void run(context) {
        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
                    def backup_type = "${context.job.getParameterValue('BACKUP_TYPE', 'manual')}"
                    script.env.NAMESPACE = context.codebase.config.name
                    script.env.edpName = context.job.edpName
                    script.sh "sh /home/jenkins/backup/backup_rclone.sh ${script.env.NAMESPACE} ${script.env.edpName} ${backup_type}"
                }
            }
        }
    }
}
return CreateBackup