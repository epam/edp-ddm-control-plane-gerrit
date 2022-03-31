import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage

@Stage(name = "deploy-via-helmfile", buildTool = ["gitops"], type = [ProjectType.CLUSTERMGMT])
class Helmfile {
    Script script

    ArrayList<String> COMPOSITE_COMPONENTS = ["user-management", "external-integration-mocks"]

    void run(context) {
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
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

                    String helmfile = 'deploy-templates/helmfile.yaml'
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
                                        git clone ${gitURL}${registry} target
                                        git clone ${gitURL}${templateURL} source
                                        cd source                        
                                        for i in \$(git branch -r | sed "s#^[ \\t]*origin/##" | grep -Ev '^master\$' | grep -Ev '^HEAD' ) ; do
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
                                                git commit -a --allow-empty -m "added branch \$i"
                                                git push --set-upstream origin \$i
                                            fi
                                        done
                                    """
                                }
                            }
                        }

                        def gerritGroupMemberYAML = script.readYaml file: 'placeholders-templates/gerrit_gerritgroupmember.yaml'
                        def gerritAdminGroup = "Administrators"
                        def gerritReadGroup = "ReadOnly"
                        def gerritAdministratorslist = script.sh(script: """oc get -n user-management KeycloakRealmUser -o json | jq -r --arg ROLE "cp-cluster-mgmt-admin" '.items[] | select(.spec.roles | index(\$ROLE)) | .metadata.name + "-${gerritAdminGroup}" + ":" + .spec.username' """, returnStdout: true).tokenize('\n')
                        def gerritReaderslist = script.sh(script: """oc get -n user-management KeycloakRealmUser -o json | jq -r --arg ROLE "cp-registry-reader" '.items[] | select(.spec.roles | index(\$ROLE)) | .metadata.name + "-${gerritReadGroup}" + ":" + .spec.username' """, returnStdout: true).tokenize('\n')
                        def gerritRoles = ["${gerritAdminGroup}": gerritAdministratorslist, "${gerritReadGroup}": gerritReaderslist]
                        def gerritUsersRemoveList = []

                        gerritRoles.each { role, users ->
                            // Assign gerrit group members
                            if (users) {
                                def gerritGroupMember = [], gerritGroupMemberList = []
                                users.eachWithIndex { username, index ->
                                    gerritGroupMember = username.tokenize(':')
                                    gerritGroupMemberList += gerritGroupMember[0]
                                    gerritGroupMemberYAML.metadata.name = gerritGroupMember[0]
                                    gerritGroupMemberYAML.metadata.namespace = script.env.globalEDPProject
                                    gerritGroupMemberYAML.metadata.labels.registry = 'cluster-mgmt'
                                    gerritGroupMemberYAML.spec.groupId = role
                                    gerritGroupMemberYAML.spec.accountId = gerritGroupMember[1]
                                    script.writeYaml file: "gerrit_gerritgroupmember-${index}.yaml", data: gerritGroupMemberYAML
                                    script.sh(""" oc apply -n ${script.env.globalEDPProject} -f gerrit_gerritgroupmember-${index}.yaml """)
                                }
                                gerritUsersRemoveList = script.sh(script: """oc get -n ${script.env.globalEDPProject} GerritGroupMember -o jsonpath='{.items[?(@.spec.groupId == "${role}")].metadata.name}' """, returnStdout: true).tokenize('\n')
                                gerritUsersRemoveList -= gerritGroupMemberList
                                gerritUsersRemoveList.each { username ->
                                    script.sh(""" oc delete --force GerritGroupMember ${username} """)
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
