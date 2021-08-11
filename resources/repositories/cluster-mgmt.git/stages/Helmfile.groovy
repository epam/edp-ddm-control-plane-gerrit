import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "deploy-openshift", buildTool = ["gitops"], type = [ProjectType.CLUSTERMGMT])
class Helmfile  {
    Script script

    void run(context) {
        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
              script.dir("${context.workDir}") {
                  script.env.NAMESPACE = context.codebase.config.name
                  script.env.ciProject = context.job.ciProject
                  script.env.dnsWildcard = context.job.dnsWildcard
                  script.env.edpName = context.job.edpName

                  script.env.edpComponentDockerRegistryUrl = context.platform.getJsonPathValue("edpcomponent", "docker-registry", ".spec.url")
                  // TODO: find more elegant way to distinct target env and our CICD env
                  script.env.globalEDPProject = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : context.job.edpName
                  script.env.edpProject = context.job.ciProject
                  script.env.dockerRegistry = script.env.edpComponentDockerRegistryUrl
                  script.env.dockerProxyRegistry = script.env.edpComponentDockerRegistryUrl
                  script.env.autoRedirectEnabled = context.job.dnsWildcard.startsWith("apps.cicd") ? 'true' : 'false'
                  script.env.AWS_ACCESS_KEY_ID = script.sh(script: """ oc get secret -n ${script.env.edpProject} backup-credentials -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' | base64 -d """ , returnStdout: true).trim()
                  script.env.AWS_SECRET_ACCESS_KEY =  script.sh(script: """ oc get secret -n ${script.env.edpProject} backup-credentials -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' | base64 -d """ , returnStdout: true).trim()
                  script.env.backupBucket = script.sh(script: """ oc get secret -n ${script.env.edpProject} backup-credentials -o jsonpath='{.data.AWS_BUCKET_NAME}' | base64 -d """ , returnStdout: true).trim()
                  script.env.dockerhub_username = script.sh(script: """ oc get secret -n openshift-config pull-secret -o jsonpath='{.data.\\.dockerconfigjson}' | base64 -d | jq -r '.auths."https://index.docker.io/v2/".username' """, returnStdout: true).trim()
                  script.env.dockerhub_password = script.sh(script: """ oc get secret -n openshift-config pull-secret -o jsonpath='{.data.\\.dockerconfigjson}' | base64 -d | jq -r '.auths."https://index.docker.io/v2/".password' """, returnStdout: true).trim()
                  script.env.CLUSTER_NAME = script.sh(script: """ oc get node -l node-role.kubernetes.io/master -o 'jsonpath={.items[0].metadata.annotations.machine\\.openshift\\.io/machine}' | sed -r 's#.*/(.*)-master.*#\\1#'""", returnStdout: true).trim()
                  script.env.platformVaultToken = script.sh(script: """ oc get secret -n ${script.env.edpProject}  vault-root-access-token -o jsonpath='{.data.vault-access-token}' """ , returnStdout: true).trim()
                  script.env.openshiftApiUrl = script.sh(script: """ oc whoami --show-server """ , returnStdout: true).trim()

                  script.env.globalNexusNamespace = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : script.env.dockerRegistry.replaceAll(/.*\.(.*)\.svc:[0-9]+/,'\$1')

                  def helmfile = 'deploy-templates/helmfile.yaml'
                  def clustermgmt = 'properties/cluster-mgmt.yaml'
                  def components = script.readYaml file: clustermgmt
                  def gitURL = "ssh://${context.git.autouser}@${context.git.host}:${context.git.sshPort}/"

                  components.releases.each {
                      script.dir('/opt/repositories/' + it.labels.path + '/' + it.name + '.git') {
                          script.checkout([$class                           : 'GitSCM', branches: [[name: 'master']],
                                           doGenerateSubmoduleConfigurations: false, extensions: [],
                                           submoduleCfg                     : [],
                                           userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                url          : gitURL + it.labels.path + it.name ]]])
                      }
                  }
                  // DON'T UNCOMMENT ON CICD* CLUSTERS
                  if (context.job.dnsWildcard.startsWith("apps.cicd")) {
                      script.println "cluster-mgmt could not be triggered on 'cicd?' clusters"
                  }
                  else {
                      script.sh("cat ${helmfile}")
                      script.sh("helmfile -f ${helmfile} sync --concurrency 1")
                  }

              }
            }
        }
    }
}
return Helmfile
