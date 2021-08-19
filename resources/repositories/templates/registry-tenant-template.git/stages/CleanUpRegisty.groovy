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

                    [   "keycloakauthflows.v1.edp.epam.com",
                        "keycloakclients.v1.edp.epam.com",
                        "keycloakrealmgroups.v1.edp.epam.com",
                        "keycloakrealmrolebatches.v1.edp.epam.com",
                        "keycloakrealmroles.v1.edp.epam.com",
                        "keycloakrealms.v1.edp.epam.com",
                        "jenkinsfolder.v2.edp.epam.com",
                        "codebasebranches.v2.edp.epam.com",
                        "keycloak.v1.edp.epam.com"
                    ].each {
                        script.println "Removing ${it}:"
                        try {
                            script.sh("oc get ${it} -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc patch ${it} -n ${script.env.NAMESPACE} --type=merge -p \\'{\\\"metadata\\\": {\\\"finalizers\\\":null}}\\'\"")
                        } catch (e) {
                            script.println("[WARN]: ${it} has not been removed or not found: ${e}")
                        }
                    }

                    [   "keycloakauthflows.v1.edp.epam.com",
                        "keycloakclients.v1.edp.epam.com",
                        "keycloakrealmgroups.v1.edp.epam.com",
                        "keycloakrealmrolebatches.v1.edp.epam.com",
                        "keycloakrealmroles.v1.edp.epam.com",
                        "keycloakrealms.v1.edp.epam.com",
                        "jenkinsfolder.v2.edp.epam.com",
                        "codebasebranches.v2.edp.epam.com",
                        "keycloak.v1.edp.epam.com"
                    ].each {
                        script.println "Removing ${it}:"
                        try {
                            script.sh("oc get ${it} -n ${script.env.NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc delete ${it} -n ${script.env.NAMESPACE}")
                        } catch (e) {
                            script.println("[WARN]: ${it} has not been removed or not found: ${e}")
                        }
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
                    
                    try {
                        script.sh "oc create ns ${script.env.NAMESPACE}"
                    } catch (e) {
                        script.sh """set -eou pipefail
                    kubectl get namespace ${script.env.NAMESPACE} -o json | jq '.spec = {"finalizers":[]}' > rknf_tmp.json
                    kubectl proxy &
                    sleep 5
                    curl -H \"Content-Type: application/json\" -X PUT --data-binary @rknf_tmp.json http://localhost:8001/api/v1/namespaces/${script.env.NAMESPACE}/finalize
                    pkill -9 -f "kubectl proxy"
                    rm rknf_tmp.json
                    """
                    }
                    try {
                        script.sh "oc create ns ${script.env.NAMESPACE}"
                    } catch (e) {
                        script.println("Can't create namespace or namespace already exists: ${e}")
                    }


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
                        try {
                            script.sh "oc adm policy add-role-to-user view system:serviceaccount:default -n ${script.env.NAMESPACE}"
                        } catch (e) {
                            script.println e
                        }
                        try {
                            script.sh "oc adm policy add-scc-to-user privileged -z default -n ${script.env.NAMESPACE}"
                        } catch (e) {
                            script.println e
                        }
                        try {
                            script.sh "oc adm policy add-scc-to-user anyuid -z default -n ${script.env.NAMESPACE}"
                        } catch (e) {
                            script.println e
                        }
                        try {
                            script.sh "oc adm policy add-role-to-user view system:serviceaccount:jenkins -n ${script.env.NAMESPACE}"
                        } catch (e) {
                            script.println e
                        }
                        try {
                            script.sh "oc adm policy add-scc-to-user privileged -z jenkins -n ${script.env.NAMESPACE}"
                        } catch (e) {
                            script.println e
                        }
                        try {
                            script.sh "oc adm policy add-scc-to-user anyuid -z jenkins -n ${script.env.NAMESPACE}"
                        } catch (e) {
                            script.println e
                        }
                    }

                }
            }
        }
    }
}
return CleanUpRegistry
