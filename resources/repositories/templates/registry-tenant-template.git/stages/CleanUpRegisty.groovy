import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "cleanup-registry-before-restore", buildTool = ["gitops"], type = [ProjectType.REGISTRY])
class CleanUpRegistry {
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
                    }
                    script.sh "helmfile --selector name=keycloak-operator -f ${helmfile} destroy --concurrency 1"
                    script.sh "sleep 20"
                    script.sh("helmfile -f ${helmfile} destroy --concurrency 1")

                    script.println "Removing finalizer from Jenkinsfolder:"
                    try {
                        script.sh("oc get jenkinsfolder.v2.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch jenkinsfolder.v2.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: Jenkinsfolder has not been patched or not found: ${e}")
                    }
                    
                    script.println "Removing finalizer from keycloakrealmrolebatches:"
                    try {
                        script.sh("oc get keycloakrealmrolebatches.v1.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch keycloakrealmrolebatches.v1.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: keycloakrealmrolebatches has not been patched or not found: ${e}")
                    }

                    script.println "Removing finalizer from keycloakrealmroles:"
                    try {
                        script.sh("oc get keycloakrealmroles.v1.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch keycloakrealmroles.v1.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: keycloakrealmroles has not been patched or not found: ${e}")
                    }

                    script.println "Removing finalizer from keycloakclients:"
                    try {
                        script.sh("oc get keycloakclients.v1.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch keycloakclients.v1.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: keycloakclients has not been patched or not found: ${e}")
                    }

                    script.println "Removing finalizer from keycloakrealms "
                    try {
                        script.sh("oc get keycloakrealms.v1.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch keycloakrealms.v1.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: keycloakrealms has not been patched or not found: ${e}")
                    }

                    script.println "Removing finalizer from keycloakrealmgroups "
                    try {
                        script.sh("oc get keycloakrealmgroups.v1.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch keycloakrealmgroups.v1.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: keycloakrealmgroups has not been patched or not found: ${e}")
                    }

                    script.println "Removing keycloak:"
                    try {
                        script.sh("oc get keycloak.v1.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch keycloak.v1.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: keycloak has not been patched or not found: ${e}")
                    }
                    script.println("Removing codebasebranches")
                    try {
                        script.sh("oc get codebasebranches.v2.edp.epam.com -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch codebasebranches.v2.edp.epam.com -n ${script.env.NAMESPACE} --type=merge -p \'{\"metadata\": {\"finalizers\":null}}\'")
                    } catch (e) {
                        script.println("[WARN]: codebasebranches has not been patched or not found: ${e}")
                    }

                    script.sh "oc delete ns ${script.env.NAMESPACE} &"
                    script.sh "sleep 20"
                    try {
                    script.sh """set -eou pipefail
                    kubectl get namespace ${script.env.NAMESPACE} -o json | jq '.spec = {"finalizers":[]}' > rknf_tmp.json
                    kubectl proxy &
                    sleep 5
                    curl -H \"Content-Type: application/json\" -X PUT --data-binary @rknf_tmp.json http://localhost:8001/api/v1/namespaces/${script.env.NAMESPACE}/finalize
                    pkill -9 -f "kubectl proxy"
                    rm rknf_tmp.json
                    """} catch (e) {
                        script.println("Can't delete namespace or namespace no found: ${e}")
                    }
                    
                    script.sh "oc create ns ${script.env.NAMESPACE}"

                    components.releases.each {
                        if (it.labels.update_scc == true) {
                            try {
                                script.sh "oc adm policy add-role-to-user view system:serviceaccount:${it.name} -n ${script.env.NAMESPACE}"
                            } catch (e) {
                                script.println e
                            }
                            try {
                                script.sh "oc adm policy add-scc-to-user privileged -z ${it.name} -n ${script.env.NAMESPACE}"
                            } catch (e) {
                                script.println e
                            }
                            try {
                                script.sh "oc adm policy add-scc-to-user anyuid -z ${it.name} -n ${script.env.NAMESPACE}"
                            } catch (e) {
                                script.println e
                            }
                        }
                    }
                }
            }
        }
    }
}
return CleanUpRegistry
