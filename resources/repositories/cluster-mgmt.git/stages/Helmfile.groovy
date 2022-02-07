import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "deploy-via-helmfile", buildTool = ["gitops"], type = [ProjectType.CLUSTERMGMT])
class Helmfile {
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
                    script.env.cloudProvider = script.sh(script: """oc get infrastructure cluster --no-headers -o jsonpath='{.status.platform}'""", returnStdout: true).trim()
                    script.env.backupBucket = script.sh(script: """ oc get secret -n ${script.env.edpProject} backup-credentials -o jsonpath='{.data.backup-s3-like-storage-location}' | base64 -d """, returnStdout: true).trim()
                    script.env.dockerhub_username = script.sh(script: """ oc get secret -n openshift-config pull-secret -o jsonpath='{.data.\\.dockerconfigjson}' | base64 -d | jq -r '.auths."https://index.docker.io/v2/".username' """, returnStdout: true).trim()
                    script.env.dockerhub_password = script.sh(script: """ oc get secret -n openshift-config pull-secret -o jsonpath='{.data.\\.dockerconfigjson}' | base64 -d | jq -r '.auths."https://index.docker.io/v2/".password' """, returnStdout: true).trim()
                    script.env.CLUSTER_NAME = script.sh(script: """ oc get node -l node-role.kubernetes.io/master -o 'jsonpath={.items[0].metadata.annotations.machine\\.openshift\\.io/machine}' | sed -r 's#.*/(.*)-master.*#\\1#'""", returnStdout: true).trim()
                    script.env.platformVaultToken = script.sh(script: """ oc get secret -n ${script.env.edpProject} vault-root-access-token -o jsonpath='{.data.vault-access-token}' """, returnStdout: true).trim()
                    script.env.openshiftApiUrl = script.sh(script: """ oc whoami --show-server """, returnStdout: true).trim()
                    script.env.platformStorageClass = script.sh(script: """ oc get storageclass -o=jsonpath='{.items[?(@.metadata.annotations.storageclass\\.kubernetes\\.io/is-default-class=="true")].metadata.name}' | awk '{print \$1}' """, returnStdout: true).trim()
                    script.env.baseDomain = script.sh(script: """oc get dns cluster --no-headers -o jsonpath='{.spec.baseDomain}'""", returnStdout: true).trim()
                    script.env.idgovuaClientId = script.sh(script: """ oc get secret -n ${script.env.edpProject} id-gov-ua-client-secret -o jsonpath='{.data.clientId}' | base64 -d -w0 """, returnStdout: true).trim()
                    script.env.idgovuaClientSecret = script.sh(script: """ oc get secret -n ${script.env.edpProject} id-gov-ua-client-secret -o jsonpath='{.data.clientSecret}' | base64 -d -w0 """, returnStdout: true).trim()

                    script.env.globalNexusNamespace = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : script.env.dockerRegistry.replaceAll(/.*\.(.*)\.svc:[0-9]+/, '\$1')

                    def helmfile = 'deploy-templates/helmfile.yaml'
                    def clustermgmt = 'properties/cluster-mgmt.yaml'
                    def usermanagementHelmfile
                    def components = script.readYaml file: clustermgmt
                    def helmfileYaml = script.readYaml file: helmfile
                    def gitURL = "ssh://${context.git.autouser}@${context.git.host}:${context.git.sshPort}/"

                    def registries = script.sh(script: """oc get codebase -n ${script.env.globalEDPProject} -o=jsonpath='{.items[?(@.spec.type == "registry")].metadata.name}'""", returnStdout: true).trim().tokenize(" ")

                    components.releases.each {
                        if (it.name == 'user-management') {
                            usermanagementHelmfile = '/opt/repositories/' + it.labels.path + '/user-management.git/deploy-templates/helmfile.yaml'
                        }
                        if (it.labels.type == "remote") {
                            script.dir('/opt/repositories/' + it.labels.path + '/' + it.name + '.git') {
                                script.checkout([$class                           : 'GitSCM', branches: [[name: it.labels.branch]],
                                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                                 submoduleCfg                     : [],
                                                 userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                      url          : gitURL + it.labels.path + it.name]]])
                            }
                        }
                    }

                    helmfileYaml.releases.each { release, releaseIndex ->
                        if (release.labels.type == "remote") {
                            script.dir('/opt/repositories/' + release.labels.path + '/' + release.name + '.git') {
                                script.checkout([$class                           : 'GitSCM', branches: [[name: release.labels.branch]],
                                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                                 submoduleCfg                     : [],
                                                 userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                      url          : gitURL + release.labels.path + release.name]]])
                            }
                        }

                        // SCC
                        if (release.labels.update_scc == true) {
                            script.sh "oc create namespace ${release.namespace} --dry-run=true -o yaml | oc apply -f -"
                            ['anyuid', 'privileged'].each { scc ->
                                try {
                                    context.platform.addSccToUser(release.name, scc, release.namespace)
                                }
                                catch (e) {
                                    script.println e
                                    def sleepSeconds = new Random().nextInt(60)
                                    script.sh "sleep ${sleepSeconds}"
                                    script.println "Trying to add SCC one more time"
                                    context.platform.addSccToUser(release.name, scc, release.namespace)
                                }

                            }
                            context.platform.createRoleBinding("system:serviceaccount:${release.name}", "view", release.namespace)
                        }
                    }

                    def usermanagementYaml = script.readYaml file: usermanagementHelmfile
                    usermanagementYaml.releases.each {
                        if (it.labels.type == "remote") {
                            script.dir('/opt/repositories/' + it.labels.path + '/' + it.name + '.git') {
                                script.checkout([$class                           : 'GitSCM', branches: [[name: it.labels.branch]],
                                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                                 submoduleCfg                     : [],
                                                 userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                      url          : gitURL + it.labels.path + it.name]]])
                            }
                        }
                    }

                    // DON'T UNCOMMENT ON CICD* CLUSTERS
                    if (context.job.dnsWildcard.startsWith("apps.cicd")) {
                        script.println "cluster-mgmt could not be triggered on 'cicd?' clusters"
                    } else {
                        script.sh("cat ${helmfile}")
                        script.sh("helmfile -f ${helmfile} -l name=ip-restrictions sync")

                        // add new branches from template to registry repo
                        def templateURL
                        script.sh("git config --global user.email \"you@example.com\"; git config --global user.name \"Admin\"")
                        registries.each { registry ->
                            templateURL = script.sh(script: "oc get codebase -n ${script.env.globalEDPProject} ${registry} -o jsonpath='{.spec.repository.url}'", returnStdout: true).trim().replaceAll(/https:\/\/.*?\\/(.*)/, "\$1")
                            script.dir(registry) {
                                script.withCredentials([script.sshUserPrivateKey(credentialsId: "${context.git.credentialsId}",
                                        keyFileVariable: 'key', passphraseVariable: '', usernameVariable: 'git_user')]) {
                                    script.sh """
                                        eval `ssh-agent`
                                        ssh-add ${script.key}
                                        mkdir -p ~/.ssh
                                        ssh-keyscan -p ${context.git.sshPort} ${context.git.host} >> ~/.ssh/known_hosts
                                        git clone ${gitURL}${registry} target
                                        git clone ${gitURL}${templateURL} source
                                        cd source                                        
                                        for i in \$(git branch -r | sed "s#^[ \\t]*origin/##" | grep -Ev '^master\$' | grep -Ev '^HEAD') ; do
                                            if [[ \$(cd ../target && git branch -r | grep -E "^[ \\t]*origin/\$i") ]]; then
                                                echo "Branch \$i exists, skipping update"
                                            else    
                                                cd ../source
                                                git checkout \$i
                                                rm -rf .git
                                                cd ../target
                                                git checkout -B \$i
                                                rm -rf ./*
                                                cp -rp ../source/* ./    
                                                git add --all
                                                git commit -a -m "added branch \$i"
                                                git push --set-upstream origin \$i
                                            fi
                                        done
                                    """
                                }
                            }

                        }

                        script.sh("helmfile -f ${helmfile} sync --concurrency 1")
                    }

                }
            }
        }
    }
}

return Helmfile
