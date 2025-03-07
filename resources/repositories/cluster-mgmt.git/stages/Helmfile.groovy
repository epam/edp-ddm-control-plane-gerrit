import com.epam.edp.customStages.helper.DeployHelper
import com.epam.edp.customStages.helper.UpgradeHelper
import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import groovy.json.JsonSlurperClassic


@Stage(name = "deploy-via-helmfile", buildTool = ["gitops"], type = [ProjectType.CLUSTERMGMT])
class Helmfile {
    Script script
    DeployHelper deployHelper
    UpgradeHelper upgradeHelper

    /**
     The Description of the function to explain what the function does
     @component - helm chart that needs to be deployed (not release name)
     @path - the name of the repository where the script is located (components/infra/user-management -> components/infra/)
     @repository - the short name of the repository where the script is located (components/infra/user-management -> user-management)
     */
    void prepareToRunPreUpgradeScripts(context, component, path, repository, ns) {
        try {
            if (deployHelper.isReleaseDeployed(component, ns)) {
                script.dir('/opt/repositories/' + path + '/' + repository + '.git') {
                    script.println("Running pre-upgrade scripts for ${component}")
                    upgradeHelper.runPreUpgradeScripts(context, component, ns)
                }
            } else {
                script.println("Skip pre-upgrade scripts for ${component} because it is not deployed")
            }
        } catch (any) {
            script.error "pre-upgrade scripts execution for ${component} has been failed"
        }
    }

    boolean processGit(context, buildUser, helmValuesPath) {
        boolean isChanged = false
        script.sshagent([context.git.credentialsId]) {
            if (script.sh(script: "git diff", returnStdout: true)) {
                isChanged = true
                script.sh "git config --global user.email ${context.git.autouser}@epam.com " +
                        "&& git config --global user.name ${context.git.autouser} " +
                        "&& git add ${helmValuesPath}" +
                        "&& gitdir=\$(git rev-parse --git-dir); scp -p -P ${context.git.sshPort} ${context.git.autouser}@${context.git.host}:hooks/commit-msg \${gitdir}/hooks/ " +
                        "&& git commit -a --allow-empty -m 'Remove section consoleVersions from values.yaml. " +
                        "Started by ${buildUser}'" +
                        "&& git push origin HEAD:${context.git.branch}"

            }
        }
        return isChanged
    }
    /**
     Delete section from values.yaml for updating control-plane-console [ONLY FOR 1.9.7 release]
     */
    void deleteConsoleVersionsFromValues(context, valuesPath){
        script.dir("${context.workDir}"){
            LinkedHashMap valuesContext = script.readYaml file: valuesPath
            valuesContext.remove('consoleVersions')
            script.writeYaml data: valuesContext, file: valuesPath, encoding: "UTF-8", overwrite: true
            processGit(context, "admin", valuesPath)

        }
    }
    ArrayList<String> COMPOSITE_COMPONENTS = ["user-management", "external-integration-mocks", "cluster-kafka-operator"]

    void placeCertificatesForKeycloak(context, String customDnsHost, String vaultPath) {
        String vaultNamespace = "user-management"
        String vaultUrl = "http://hashicorp-vault.user-management.svc.cluster.local:8200"
        String vaultToken = (new String(context.platform.getJsonPathValue("secrets", "vault-root-token",
                ".data.VAULT_ROOT_TOKEN", vaultNamespace).decodeBase64()))
        String keycloakChartPath = "/opt/repositories/components/infra/keycloak.git/deploy-templates"
        String certificateFolderName = customDnsHost.replace(".","-")

        def secretDataResponse = script.httpRequest url: vaultUrl + "/v1/" + vaultPath.replaceFirst('/', '/data/'),
                httpMode: 'GET',
                customHeaders: [[name: 'X-Vault-Token', value: "${vaultToken}"]],
                validResponseCodes: '200,404',
                quiet: true

        if (secretDataResponse.status.equals(200)) {
            def folder = new File("${keycloakChartPath}/certificates/${certificateFolderName}")
            new JsonSlurperClassic().parseText(secretDataResponse.content).data.data.each { secretKey, secretValue ->
                script.dir("${folder}") {
                    script.writeFile(file: secretKey, text: secretValue)
                }
            }
        }
    }

    void run(context) {
        deployHelper = new DeployHelper(script)
        upgradeHelper = new UpgradeHelper(script)
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
                    script.env.dockerhub_username = script.sh(script: """ oc get secret -n openshift-config pull-secret -o jsonpath='{.data.\\.dockerconfigjson}' | base64 -d | jq -r '.auths | with_entries(select(.key|test("docker.io"))) | first(.[]).username' """, returnStdout: true).trim()
                    script.env.dockerhub_password = script.sh(script: """ oc get secret -n openshift-config pull-secret -o jsonpath='{.data.\\.dockerconfigjson}' | base64 -d | jq -r '.auths | with_entries(select(.key|test("docker.io"))) | first(.[]).password' """, returnStdout: true).trim()
                    script.env.CLUSTER_NAME = script.sh(script: """ oc get node -l node-role.kubernetes.io/master -o 'jsonpath={.items[0].metadata.annotations.machine\\.openshift\\.io/machine}' | sed -r 's#.*/(.*)-master.*#\\1#'""", returnStdout: true).trim()
                    script.env.platformVaultToken = script.sh(script: """ oc get secret -n ${script.env.edpProject} vault-root-access-token -o jsonpath='{.data.vault-access-token}' """, returnStdout: true).trim()
                    script.env.openshiftApiUrl = script.sh(script: """ oc whoami --show-server """, returnStdout: true).trim()
                    script.env.platformStorageClass = script.sh(script: """ oc get storageclass -o=jsonpath='{.items[?(@.metadata.annotations.storageclass\\.kubernetes\\.io/is-default-class=="true")].metadata.name}' | awk '{print \$1}' """, returnStdout: true).trim()
                    script.env.baseDomain = script.sh(script: """oc get dns cluster --no-headers -o jsonpath='{.spec.baseDomain}'""", returnStdout: true).trim()
                    script.env.ID_GOV_UA_CLIENT_ID = script.sh(script: """ oc get secret -n ${script.env.edpProject} id-gov-ua-client-secret -o jsonpath='{.data.clientId}' | base64 -d -w0 """, returnStdout: true).trim()
                    script.env.ID_GOV_UA_CLIENT_SECRET = script.sh(script: """ oc get secret -n ${script.env.edpProject} id-gov-ua-client-secret -o jsonpath='{.data.clientSecret}' | base64 -d -w0 """, returnStdout: true).trim()
                    script.env.globalNexusNamespace = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : script.env.dockerRegistry.replaceAll(/.*\.(.*)\.svc:[0-9]+/, '\$1')
                    script.env.ADMIN_ROUTES_WHITELIST_CIDR = values.global.whiteListIP.adminRoutes
                    script.env.PLATFORM_DEPLOYMENT_MODE = values.global.deploymentMode

                    String helmfilePath = 'deploy-templates/helmfile.yaml'
                    String helmValuesPath = 'deploy-templates/values.yaml'
                    LinkedHashMap helmValues = script.readYaml file: helmValuesPath
                    String clustermgmt = 'properties/cluster-mgmt.yaml'
                    String gitURL = "ssh://${context.git.autouser}@${context.git.host}:${context.git.sshPort}/"
                    LinkedHashMap components = script.readYaml file: clustermgmt
                    LinkedHashMap helmfile = script.readYaml file: helmfilePath
                    components.releases.addAll(helmfile.releases)
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
                    if (helmValues.'digital-signature') {
                        deployHelper.exportDigitalSignatureSecretsInTargetV2(context, helmValues, "user-management", context.workDir)
                        deployHelper.exportDigitalSignatureKeysInTarget(context, helmValues, "user-management", context.workDir)
                    }

                    if (helmValues.keycloak) {
                        helmValues.keycloak.customHosts.each {
                            placeCertificatesForKeycloak(context, it.host, it.certificatePath)
                        }
                    }
                    if (helmValues.'consoleVersions')
                        deleteConsoleVersionsFromValues(context, helmValuesPath)

                    // DON'T UNCOMMENT ON CICD* CLUSTERS
                    if (context.job.dnsWildcard.startsWith("apps.cicd")) {
                        script.println "cluster-mgmt could not be triggered on 'cicd?' clusters"
                    } else {
                        script.sh("cat ${helmfilePath}")

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

                        //temporary solution. Move to pre-upgrade script when implemented
                        try {
                            deployHelper.annotateCephConfig(context)
                        } catch (any) {
                            script.println("WARN: failed to annotate ceph config map. Skipping")
                        }
                        components.releases.each() { release ->

                            // For keycloak, keycloak-idps in user-management ns by now
                            if (release.name == "user-management") {
                                prepareToRunPreUpgradeScripts(context, "keycloak", release.labels.path, "keycloak", release.name)
                                prepareToRunPreUpgradeScripts(context, "keycloak-idps", release.labels.path, release.name, release.name)
                                if (helmValues.global.region == "global") {
                                    String umHelmfilePath = "/opt/repositories/${release.labels.path}${release.name}.git/deploy-templates/helmfile.yaml"
                                    LinkedHashMap umYaml = script.readYaml file: umHelmfilePath
                                    umYaml["releases"].find { it.name == "digital-signature-ops" }.installed = false
                                    script.writeYaml data: umYaml, file: umHelmfilePath, overwrite: true
                                }
                            }
                        }
                        script.sh("helmfile -f ${helmfilePath} sync --values ${context.workDir}/deploy-templates/values.yaml --concurrency 1")
                    }
                    LinkedHashMap routes = [
                            'grafana'                           : 'openshift-monitoring',
                            'prometheus-k8s'                    : 'openshift-monitoring',
                            'prometheus-k8s-federate'           : 'openshift-monitoring',
                            'alertmanager-main'                 : 'openshift-monitoring',
                            'thanos-querier'                    : 'openshift-monitoring',
                            'kibana'                            : 'openshift-logging',
                            'console'                           : 'openshift-console',
                            'noobaa-mgmt'                       : 'openshift-storage',
                            'ocs-storagecluster-cephobjectstore': 'openshift-storage',
                            's3'                                : 'openshift-storage',
                            'oauth-openshift'                   : 'openshift-authentication',
                            'jaeger'                            : 'istio-system',
                            'gerrit'                            : 'control-plane',
                            'jenkins'                           : 'control-plane',
                            'control-plane-console'             : 'control-plane',
                            'platform-vault'                    : 'control-plane',
                            'platform-minio-ui'                 : 'control-plane',
                            'platform-minio'                    : 'control-plane',
                            'ddm-architecture'                  : 'documentation',
                            'mailu-admin'                       : 'smtp-server',
                            'mailu-web'                         : 'smtp-server'
                    ]
                    routes.each {
                        try {
                            script.sh "oc annotate route ${it.key} --overwrite -n ${it.value} " +
                                    "haproxy.router.openshift.io/ip_whitelist=\"${script.env.ADMIN_ROUTES_WHITELIST_CIDR}\"\n"
                        } catch (any) {
                            script.println("WARN: failed to annotate route ${it.key} in namespace ${it.value}")
                        }
                    }
                }
            }
        }
    }
}

return Helmfile
