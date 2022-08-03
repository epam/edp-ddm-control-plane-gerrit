import com.epam.edp.customStages.helper.DeployHelper
import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "deploy-via-helmfile", buildTool = ["gitops"], type = [ProjectType.CLUSTERMGMT])
class Helmfile {
    Script script
    DeployHelper deployHelper

    ArrayList<String> COMPOSITE_COMPONENTS = ["user-management", "external-integration-mocks", "cluster-kafka-operator"]

    void run(context) {
        deployHelper = new DeployHelper(script)
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
                    def values = script.readYaml file: "deploy-templates/values.yaml"
                    script.env.NAMESPACE = context.codebase.config.name
                    script.env.ciProject = context.job.ciProject
                    script.env.dnsWildcard = context.job.dnsWildcard
                    script.env.edpName = context.job.edpName
                    script.env.edpComponentDockerRegistryUrl = context.platform.getJsonPathValue("edpcomponent",
                            "docker-registry", ".spec.url")
                    // TODO: find more elegant way to distinct target env and our CICD env
                    script.env.globalEDPProject = context.job.dnsWildcard.startsWith("apps.cicd") ?
                            'mdtu-ddm-edp-cicd' : context.job.edpName
                    script.env.edpProject = context.job.ciProject
                    script.env.dockerRegistry = script.env.edpComponentDockerRegistryUrl
                    script.env.dockerProxyRegistry = script.env.edpComponentDockerRegistryUrl
                    script.env.autoRedirectEnabled = context.job.dnsWildcard.startsWith("apps.cicd")
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
                    script.env.ADMIN_ROUTES_WHITELIST_CIDR = values.global.whiteListIP.adminRoutes

                    String helmfile = 'deploy-templates/helmfile.yaml'
                    String helmValuesPath = 'deploy-templates/values.yaml'
                    String clustermgmt = 'properties/cluster-mgmt.yaml'
                    String gitURL = "ssh://${context.git.autouser}@${context.git.host}:${context.git.sshPort}/"
                    LinkedHashMap components = script.readYaml file: clustermgmt
                    ArrayList<String> registries = script.sh(script: """oc get codebase -n ${script.env.globalEDPProject} -o=jsonpath='{.items[?(@.spec.type == "registry")].metadata.name}'""", returnStdout: true).trim().tokenize(" ")

                    components.releases.each { component ->
                        if (component.labels.type == "remote") {
                            script.dir('/opt/repositories/' + component.labels.path + '/' + component.name + '.git') {
                                script.checkout([$class                           : 'GitSCM', branches: [[name: component.labels.branch]],
                                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                                 submoduleCfg                     : [],
                                                 userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                      url          : gitURL + component.labels.path + component.name]]])
                            }
                        }

                        COMPOSITE_COMPONENTS.each { cc ->
                            if (component.name == cc) {
                                String ccHelmfile = "/opt/repositories/${component.labels.path}/${cc}.git/deploy-templates/helmfile.yaml"
                                LinkedHashMap ccYaml = script.readYaml file: ccHelmfile
                                ccYaml.releases.each { release ->
                                    if (release.labels.type == "remote") {
                                        script.dir('/opt/repositories/' + release.labels.path + '/' + release.name + '.git') {
                                            script.checkout([$class                           : 'GitSCM', branches: [[name: release.labels.branch]],
                                                             doGenerateSubmoduleConfigurations: false, extensions: [],
                                                             submoduleCfg                     : [],
                                                             userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                                  url          : gitURL + release.labels.path + release.name]]])
                                        }
                                    }
                                }
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
                        String templateURL
                        script.sh("git config --global user.email \"you@example.com\"; git config --global user.name \"Admin\"")
                        registries.each { registry ->
                            templateURL = script.sh(script: """oc get codebase -n ${script.env.globalEDPProject} ${registry} -o jsonpath='{.metadata.annotations.registry-parameters/template-name}'""", returnStdout: true).trim()
                            script.dir(registry) {
                                script.sshagent(["${context.git.credentialsId}"]) {
                                    script.sh """
                                        mkdir -p ~/.ssh
                                        ssh-keyscan -p ${context.git.sshPort} ${context.git.host} >> ~/.ssh/known_hosts
                                        git clone ${gitURL}${registry} ${context.workDir}/${registry}/target/master
                                        git clone ${gitURL}${templateURL} ${context.workDir}/${registry}/source/master
             
                                        for i in \$(cd ${context.workDir}/${registry}/source/master && git branch -r | sed "s#^[ \\t]*origin/##" | grep -Ev '^master\$' | grep -Ev '^HEAD' ) ; do
                                            if [[ \$(cd ${context.workDir}/${registry}/target/master && git branch -r | grep -E "^[ \\t]*origin/\$i") ]]; then
                                                echo "Branch \$i exists, skipping update"
                                            else    
                                                git clone -b \$i ${gitURL}${templateURL} ${context.workDir}/${registry}/source/${registry}-\$i \
                                                && rm -rf ${context.workDir}/${registry}/source/${registry}-\$i/.git
                                                
                                                git clone ${gitURL}${registry} ${context.workDir}/${registry}/target/${registry}-\$i \
                                                && cd ${context.workDir}/${registry}/target/${registry}-\$i \
                                                && git checkout -B \$i \
                                                && rm -rfv !\\(".git"\\) \
                                                && cp -rp "${context.workDir}/${registry}/source/${registry}-\${i}" "${context.workDir}/${registry}/target/" \
                                                && git add --all \
                                                && git commit -a --allow-empty -m "added branch \$i from template" \
                                                && git push --set-upstream origin \$i \
                                                && cd ${context.workDir}/${registry} \
                                                && rm -rf ${context.workDir}/${registry}/target/${registry}-\$i \
                                                && rm -rf ${context.workDir}/${registry}/source/${registry}-\$i
                                            fi
                                        done

                                        rm -rf ${context.workDir}/${registry}/source
                                        rm -rf ${context.workDir}/${registry}/target
                                    """
                                }
                            }
                        }

                        deployHelper.createClusterAdmin(helmValuesPath, context)

                        script.sh("helmfile -f ${helmfile} sync --concurrency 1")
                    }
                    LinkedHashMap routes = [
                            'grafana' : 'openshift-monitoring',
                            'prometheus-k8s' : 'openshift-monitoring',
                            'alertmanager-main' : 'openshift-monitoring',
                            'thanos-querier' : 'openshift-monitoring',
                            'kibana' : 'openshift-logging',
                            'console' : 'openshift-console',
                            'noobaa-mgmt' : 'openshift-storage',
                            's3' : 'openshift-storage',
                            'oauth-openshift' : 'openshift-authentication',
                            'jaeger' : 'istio-system',
                            'gerrit' : 'control-plane',
                            'jenkins' : 'control-plane',
                            'control-plane-console' : 'control-plane',
                            'ddm-architecture' : 'documentation'
                    ]
                    routes.each {
                        script.sh "oc annotate route ${it.key} --overwrite -n ${it.value} " +
                                "haproxy.router.openshift.io/ip_whitelist=\"${script.env.ADMIN_ROUTES_WHITELIST_CIDR}\"\n"
                    }
                }
            }
        }
    }
}

return Helmfile
