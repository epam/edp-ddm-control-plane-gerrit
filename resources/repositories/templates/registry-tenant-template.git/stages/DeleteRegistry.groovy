import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "delete-registry", buildTool = ["gitops"], type = [ProjectType.REGISTRY])
class DeleteRegistry {
    Script script

    void run(context) {
        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
                    script.env.NAMESPACE = context.codebase.config.name
                    script.env.dnsWildcard = script.env.NAMESPACE + '.' + context.job.dnsWildcard
                    def helmfile = 'deploy-templates/helmfile.yaml'
                    def components = script.readYaml file: helmfile
                    def gitURL = "ssh://${context.git.autouser}@${context.git.host}:${context.git.sshPort}/components/registry/"

                    def codebases = script.sh(script: "oc get codebase -n ${script.env.NAMESPACE} --no-headers -o=custom-columns=NAME:.metadata.name", returnStdout: true).tokenize('\n')
                    def jenkinsFolders = script.sh(script: "oc get jenkinsfolders -n ${script.env.NAMESPACE} --no-headers -o=custom-columns=NAME:.metadata.name", returnStdout: true).tokenize('\n')
                    def codebasebranches = script.sh(script: "oc get codebasebranches -n ${script.env.NAMESPACE} --no-headers -o=custom-columns=NAME:.metadata.name", returnStdout: true).tokenize('\n')

                    components.releases.each {
                        if (it.labels.type == 'remote') {
                            script.dir('/opt/repositories/' + it.name) {
                                script.checkout([$class                           : 'GitSCM', branches: [[name: 'master']],
                                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                                 submoduleCfg                     : [],
                                                 userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                      url          : gitURL + it.name]]])
                            }
                        }

                        //removing SCC
                        if (it.labels.update_scc == true) {
                            try {
                                script.sh "oc adm policy remove-role-from-user view system:serviceaccount:${it.name} -n ${script.env.NAMESPACE}"
                            } catch (e) {
                                script.println e
                            }
                            try {
                                script.sh "oc adm policy remove-scc-from-user privileged -z ${it.name} -n ${script.env.NAMESPACE}"
                            } catch (e) {
                                script.println e
                            }
                            try {
                                script.sh "oc adm policy remove-scc-from-user anyuid -z ${it.name} -n ${script.env.NAMESPACE}"
                            } catch (e) {
                                script.println e
                            }
                        }
                    }

                    script.println "Removing codebasebranches:"
                    codebasebranches.each {
                        try {
                            script.sh """oc patch codebasebranches.v2.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p '{"metadata": {"finalizers":null}}' ${it} && oc delete -n ${script.env.NAMESPACE} codebasebranches ${it}"""
                        }
                        catch (any) {
                            script.println("[WARN]: Codebasebranch ${it} has not been removed or not found")
                        }
                    }

                    script.println "Removing jenkinsfolders:"
                    jenkinsFolders.each {
                        try {
                            script.sh """ oc patch jenkinsfolders -n ${script.env.NAMESPACE} --type=merge -p '{"metadata": {"finalizers":null}}' ${it} && oc delete -n ${script.env.NAMESPACE} jenkinsfolders ${it} """
                        }
                        catch (any) {
                            script.println("[WARN]: jenkinsfolder ${it} has not been removed or not found")
                        }
                    }

                    script.println "Removing codebases:"
                    codebases.each {
                        try {
                            script.sh """ oc patch codebase -n ${script.env.NAMESPACE} --type=merge -p '{"metadata": {"finalizers":null}}' ${it} && oc delete -n ${script.env.NAMESPACE} codebase ${it} """
                        }
                        catch (any) {
                            script.println("[WARN]: Codebase ${it} has not been removed or not found")
                        }
                    }

                    // we must ensure that keycloak will be removed after everything else
                    script.sh("helmfile --selector name!=keycloak-operator -f ${helmfile} destroy --concurrency 1")
                    script.sh "sleep 20"

                    [   "keycloakauthflows.v1.edp.epam.com",
                        "keycloakclients.v1.edp.epam.com",
                        "keycloakrealmgroups.v1.edp.epam.com",
                        "keycloakrealmrolebatches.v1.edp.epam.com",
                        "keycloakrealmroles.v1.edp.epam.com",
                        "keycloakrealms.v1.edp.epam.com",
                        "keycloak.v1.edp.epam.com"
                    ].each {
                        script.println "Removing ${it}:"
                        try {
                            script.sh("oc get ${it} -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc delete ${it} -n ${script.env.NAMESPACE}")
                        } catch (e) {
                            script.println("[WARN]: ${it} has not been removed or not found: ${e}")
                        }
                    }


                    script.sh "sleep 20"
                    script.sh("helmfile -f ${helmfile} destroy --concurrency 1")

                    script.sshagent(credentials: [context.git.credentialsId]) {
                        // removing repos from gerrit
                        try {
                            script.sh("ssh -p ${context.git.sshPort} ${context.git.autouser}@${context.git.host} delete-project delete --yes-really-delete --force ${script.env.NAMESPACE}")
                            script.println("Gerrit project \"${script.env.NAMESPACE}\" has been removed.")
                        } catch (any) {
                            script.println("[WARN]: gerrit project \"${script.env.NAMESPACE}\" has not been removed")
                        }
                    }

                    script.sh "oc delete ns ${script.env.NAMESPACE}"
                }
            }
        }
    }
}

return DeleteRegistry
