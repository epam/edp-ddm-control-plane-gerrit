import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "deploy-openshift", buildTool = ["gitops"], type = [ProjectType.REGISTRY])
class Helmfile {
    Script script

    int getFreeNodePort() {
        def usedPorts = script.sh(
                script: "oc get svc --all-namespaces -o go-template='{{range .items}}{{range.spec.ports}}" +
                        "{{if .nodePort}}{{.nodePort}}{{\" \"}}{{end}}{{end}}{{end}}'",
                returnStdout: true
        ).trim().split(" ")

        def templatePorts = script.sh(
                script: "oc get template --all-namespaces -o jsonpath='{.items[?(@name like \"additional-tools\")]." +
                        "objects[?(@.kind==\"Gerrit\")].spec.sshPort}'",
                returnStdout: true
        ).trim().split(" ")

        def freePort = new Random().nextInt(2766) + 30000
        while (freePort in (usedPorts + templatePorts)) {
            freePort = new Random().nextInt(2766) + 30000
        }

        return freePort
    }

    // gerrit-operator
    def setGerritNodePort(context, codebaseDir) {
        def sshNodePort
        try {
            sshNodePort = context.platform.getJsonPathValue("gerrit", "gerrit", ".spec.sshPort", context.codebase.config.name)
        }
        catch (e) {
            sshNodePort = getFreeNodePort()
            script.println "[WARN]: Gerrit is not deployed, setting random port ${sshNodePort}"
            script.println e
        }
        script.sh("sed -i -e 's/  sshPort:.*\$/  sshPort: ${sshNodePort}/' " +
                "${codebaseDir}/${context.job.deployTemplatesDirectory}/values.yaml")
    }

    def removeOwnerReference(resourceType, resourceName, namespace = null) {
        def command = "oc patch ${resourceType} ${resourceName} --type merge -p \"{\\\"metadata\\\":" +
                "{\\\"ownerReferences\\\":[]}}\""

        if (namespace)
            command = "${command} -n ${namespace}"

        script.sh(script: "${command}")
    }


    def exportKeycloakSecret(context) {
        if (context.platform.checkObjectExists("secret", "keycloak", script.env.NAMESPACE)) {
            script.println("[JENKINS][DEBUG] Keycloak secret for SSO integration already created")
        } else {
            script.println("[JENKINS][DEBUG] Create keycloak secret for SSO integration")
            def secretName = context.job.dnsWildcard.startsWith("apps.cicd") ? "keycloak-platform" : 'keycloak'
            def username = new String(context.platform.getJsonPathValue("secret", secretName, ".data.username",
                    script.env.globalEDPProject).decodeBase64())
            def password = new String(context.platform.getJsonPathValue("secret", secretName, ".data.password",
                    script.env.globalEDPProject).decodeBase64())
            script.sh "set +x; oc -n ${script.env.NAMESPACE} create secret generic keycloak " +
                    "--from-literal='username=${username}' --from-literal='password=${password}'; set -x"
        }
    }

    def copySecret(context, sourceNamespace, sourceSecret, targetNamespace, targetSecret) {
        script.sh """
                  oc get secret ${sourceSecret} --namespace=${sourceNamespace} -o json | \\
                  jq 'del(.metadata.namespace,.metadata.resourceVersion,.metadata.uid,.metadata.managedFields,.metadata.selfLink,.metadata.ownerReferences) | .metadata.creationTimestamp=null | .metadata.name |= "${targetSecret}"' | \\
                  oc replace --namespace=${targetNamespace} --force -f -
                  """
    }

    def exportDigitalSignatureSecrets(context) {
        def secretJSON
        try {
            secretJSON = script.readJSON text: script.sh (script: "oc get secret system-digital-sign-${script.env.NAMESPACE}-key -n ${script.env.edpProject} -o json | jq 'del(.metadata.namespace,.metadata.resourceVersion,.metadata.uid) | .metadata.creationTimestamp=null'", returnStdout: true)
            secretJSON.metadata.name = "digital-signature-key"
            script.writeJSON file: 'digital-signature-key', json: secretJSON
            script.sh "oc replace -n ${script.env.NAMESPACE} --force -f digital-signature-key"

            secretJSON = script.readJSON text: script.sh (script: "oc get secret system-digital-sign-${script.env.NAMESPACE}-ca -n ${script.env.edpProject} -o json | jq 'del(.metadata.namespace,.metadata.resourceVersion,.metadata.uid,.metadata.managedFields,.metadata.selfLink,.metadata.ownerReferences) | .metadata.creationTimestamp=null'", returnStdout: true)
            secretJSON.metadata.name = "digital-signature-ca"
            script.writeJSON file: 'digital-signature-ca', json: secretJSON
            script.sh "oc replace -n ${script.env.NAMESPACE} --force -f digital-signature-ca"


            script.sh "oc delete secret -n ${script.env.edpProject} system-digital-sign-${script.env.NAMESPACE}-key"
            script.sh "oc delete secret -n ${script.env.edpProject} system-digital-sign-${script.env.NAMESPACE}-ca"
        }
        catch (e) {
            script.println e
            script.sh "oc get secret -n ${script.env.NAMESPACE} digital-signature-key"
            script.sh "oc get secret -n ${script.env.NAMESPACE} digital-signature-ca"
        }
        finally {
            script.sh "rm -rf digital-signature-ca digital-signature-key"
        }
    }


    def cloneCodebaseSecrets(context, def codebases = []) {
        codebases.each {
            if (context.platform.checkObjectExists("secret", "repository-codebase-${it}-temp", script.env.NAMESPACE)) {
                script.println("[JENKINS][DEBUG] Codebase ${it} secret is already created")
            } else {
                script.println("[JENKINS][DEBUG] Creating codebase ${it} secret")
                // TODO: find more elegant way to distinct target env and our CICD env
                def secretName = "gerrit-project-creator-password"
                def username = new String(context.platform.getJsonPathValue("secret", secretName, ".data.user",
                        script.env.edpProject).decodeBase64())
                def password = new String(context.platform.getJsonPathValue("secret", secretName, ".data.password",
                        script.env.edpProject).decodeBase64())
                script.sh "set +x; oc -n ${script.env.NAMESPACE} create secret generic repository-codebase-${it}-temp " +
                        "--from-literal='username=${username}' --from-literal='password=${password}'; set -x"
            }
        }

    }


    void run(context) {
        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
                    script.env.NAMESPACE = context.codebase.config.name
                    script.env.ciProject = context.job.ciProject
                    script.env.dnsWildcard = context.job.dnsWildcard
                    script.env.edpName = context.job.edpName
                    script.env.cdPipelineName = context.codebase.config.name

                    def helmfile = 'deploy-templates/helmfile.yaml'
                    def components = script.readYaml file: helmfile
                    def gitURL = "ssh://${context.git.autouser}@${context.git.host}:${context.git.sshPort}/components/registry/"

                    // TODO: find more elegant way to distinct target env and our CICD env
                    script.env.globalEDPProject = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : context.job.edpName
                    script.env.edpComponentDockerRegistryUrl = context.job.dnsWildcard.startsWith("apps.cicd") ? 'nexus-docker-registry.' + context.job.dnsWildcard : context.platform.getJsonPathValue("edpcomponent", "docker-registry", ".spec.url")
                    script.env.autoRedirectEnabled = context.job.dnsWildcard.startsWith("apps.cicd") ? 'true' : 'false'

                    script.env.edpProject = context.job.ciProject
                    script.env.dockerRegistry = script.env.edpComponentDockerRegistryUrl
                    script.env.dockerProxyRegistry = script.env.edpComponentDockerRegistryUrl

                    script.env.globalNexusNamespace = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : script.env.dockerRegistry.replaceAll(/.*\.(.*)\.svc:[0-9]+/,'\$1')
                    script.env.edpMavenRepoUrl = context.job.dnsWildcard.startsWith("apps.cicd") ? "http://nexus.mdtu-ddm-edp-cicd.svc:8081/repository/edp-maven-group/" : "http://nexus.${script.env.globalNexusNamespace}.svc:8081/nexus/repository/edp-maven-group/"

                    def openshiftClusterMasterMachineJSON = script.readJSON(text: script.sh(script: """ oc get machine -n openshift-machine-api -l machine.openshift.io/cluster-api-machine-role=master --no-headers -o json """, returnStdout: true).trim()).items

                    script.env.CLUSTER_NAME = openshiftClusterMasterMachineJSON[0].metadata.labels."machine.openshift.io/cluster-api-cluster"
                    script.env.cloudProvider = openshiftClusterMasterMachineJSON[0].spec.providerSpec.value.kind.replaceAll('MachineProviderConfig','')

                    openshiftClusterMasterMachineJSON.metadata.labels.eachWithIndex { label, index ->
                        def registryMachineSet = script.readFile file: "placeholders-templates/${script.env.cloudProvider}/registry-machine-set.yaml"
                        registryMachineSet = registryMachineSet.replaceAll('\\{\\{ .Values.node_zone \\}\\}', label."machine.openshift.io/zone").replaceAll('\\{\\{ .Values.node_region \\}\\}', label."machine.openshift.io/region")
                        script.sh "rm -rf deploy-templates/registry-nodes/templates/registry-machine-set*"
                        script.println registryMachineSet
                        script.writeFile file: "deploy-templates/registry-nodes/templates/registry-machine-set-" + label."machine.openshift.io/region" + '-' + label."machine.openshift.io/zone" + '.yaml', text: registryMachineSet
                    }

                    script.env.platformVaultToken = script.sh(script: """ oc get secret -n ${script.env.edpProject}  vault-root-access-token -o jsonpath='{.data.vault-access-token}' """ , returnStdout: true).trim()
                    script.env.openshiftApiUrl = script.sh(script: """ oc whoami --show-server """ , returnStdout: true).trim()


                    // run namespace creation and basic management tasks
                    script.sh("helmfile -f ${helmfile} -l name=istio-configuration -l name=registry-auth sync")
                    script.sh (""" oc annotate namespace ${script.env.NAMESPACE} 'scheduler.alpha.kubernetes.io/defaultTolerations'='[{"operator": "Exists", "key": "node/${script.env.NAMESPACE}"}]' --overwrite """)
                    script.sh (""" oc annotate namespace ${script.env.NAMESPACE} 'scheduler.alpha.kubernetes.io/node-selector'='node=${script.env.NAMESPACE}' --overwrite """)

                    def gerritAdministratorslist = (new String(context.platform.getJsonPathValue("codebase", context.codebase.config.name, ".metadata.annotations.registry-parameters/administrators",
                            script.env.edpName).decodeBase64())).tokenize(',')

                    def keycloakRealmUserYAML = script.readYaml file: 'placeholders-templates/KeycloakRealmUser.yaml'
                    keycloakRealmUserYAML.spec.groups = ["cp-${script.env.NAMESPACE}-admins"]
                    gerritAdministratorslist.eachWithIndex {registryAdmin, index ->
                        keycloakRealmUserYAML.metadata.name = "${registryAdmin}".replaceAll("^[^a-z0-9]*|[^a-z0-9]*\$", '').replaceAll("[^a-z0-9\\.-]",'')
                        keycloakRealmUserYAML.spec.username = registryAdmin
                        keycloakRealmUserYAML.spec.email = registryAdmin
                        script.writeYaml file: "KeycloakRealmUser-${index}.yaml", data: keycloakRealmUserYAML
                        script.sh(""" oc apply -n ${script.env.NAMESPACE} -f KeycloakRealmUser-${index}.yaml """)
                    }

                    copySecret(context, script.env.edpProject, 'gerrit-ciuser-sshkey', script.env.NAMESPACE, "gerrit-${script.env.edpProject}-sshkey")


                    components.releases.eachWithIndex { release, releaseIndex ->
                        script.println("[JENKINS][DEBUG] processing component ${release.name}")
                        if (release.labels.type == 'remote') {
                            script.dir('/opt/repositories/' + release.name) {
                                script.checkout([$class                           : 'GitSCM', branches: [[name: 'master']],
                                                 doGenerateSubmoduleConfigurations: false, extensions: [],
                                                 submoduleCfg                     : [],
                                                 userRemoteConfigs                : [[credentialsId: context.git.credentialsId,
                                                                                      url          : gitURL + release.name]]])
                            }
                        }

                        // SCC
                        if (release.labels.update_scc == true) {
                            ['anyuid', 'privileged'].each { scc ->
                                try {
                                    context.platform.addSccToUser(release.name, scc, context.codebase.config.name)
                                }
                                catch (e) {
                                    script.println e
                                    def sleepSeconds = new Random().nextInt(60)
                                    script.sh "sleep ${sleepSeconds}"
                                    script.println "Trying to add SCC one more time"
                                    context.platform.addSccToUser(release.name, scc, context.codebase.config.name)
                                }

                            }
                            context.platform.createRoleBinding("system:serviceaccount:${release.name}", "view", context.codebase.config.name)
                        }

                        // gerrit-operator
                        if (release.name == "gerrit-operator") {
                            setGerritNodePort(context, '/opt/repositories/gerrit-operator')
                            def gerritAdministratorValueIndex = release.values.findIndexOf { it instanceof Map && it.gerrit?.administrators }
                            if (gerritAdministratorValueIndex >= 0) {
                                components.releases[releaseIndex].values[gerritAdministratorValueIndex].global.gerrit.administrators += gerritAdministratorslist
                                components.releases[releaseIndex].values[gerritAdministratorValueIndex].global.gerrit.administrators.unique()
                            } else {
                                release.values.add(global: [gerrit: [administrators: gerritAdministratorslist.unique()]])
                            }
                        }

                        if (release.name == 'keycloak-operator') {
                            exportKeycloakSecret(context)
                        }

                        if (release.name == "digital-signature-ops") {
                            exportDigitalSignatureSecrets(context)
                        }


                    }

                    script.writeYaml data: components, file: helmfile, overwrite: true

                    script.sh "cat ${helmfile}"
                    script.sh("helmfile -f ${helmfile} sync")

                    try {
                        removeOwnerReference("clusterrole", "jenkins-${context.codebase.config.name}-cluster-role")
                    } catch (any) {
                        script.println("[WARN]: ${any}")
                    }


                    def codebases = script.sh(script: "oc get codebase -n ${script.env.NAMESPACE} --no-headers -o=custom-columns=NAME:.metadata.name", returnStdout: true).tokenize('\n')
                    cloneCodebaseSecrets(context, codebases)
                    copySecret(context, script.env.NAMESPACE, "repository-codebase-${codebases[0]}-temp", script.env.NAMESPACE, "edp-gerrit-ciuser")
                }
            }
        }
    }
}

return Helmfile
