/*
 * Copyright 2021 EPAM Systems.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.edp.stages.impl.ci.impl.builddockerfileimage

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.epam.edp.stages.impl.ci.impl.codebaseiamgestream.CodebaseImageStreams

@Stage(name = "build-image-from-dockerfile", buildTool = ["gitops"], type = [ProjectType.APPLICATION])
class BuildDockerfileImageHelm {
    Script script

    def buildConfigApi = "buildconfig", stageCRJSON

    void createOrUpdateBuildConfig(codebase, buildConfigName, imageUrl) {
        if (!script.openshift.selector(buildConfigApi, "${buildConfigName}").exists()) {
            script.openshift.newBuild(codebase.imageBuildArgs)
            return
        }
        script.sh("oc patch --type=merge ${buildConfigApi} ${buildConfigName} -p \"{\\\"spec\\\":{\\\"output\\\":{\\\"to\\\":{\\\"name\\\":\\\"${imageUrl}\\\"}}}}\" ")
    }

    void processHelmfile(context, def helmfile) {
        def helmfileYAML = script.readYaml file: helmfile
        def repositoryPath, gitCredentialsId, imageURL

        helmfileYAML.releases.eachWithIndex { release, releaseIndex ->
            script.println "Processing release: ${release.name}"
            if (release.labels.type == 'remote') {
                def gitBranch = (release.version == 'master' || release.labels.isbranch == true) ? release.version : 'build/' + release.version
                repositoryPath = release.labels.path.endsWith('/') || release.labels.path == '' ? release.labels.path + release.name + '.git' : release.labels.path + '/' + release.name + '.git'
                gitCredentialsId = release.labels.repoURL.contains('gitbud.epam.com') ? 'git-epam-ciuser-sshkey' : 'gerrit-ciuser-sshkey'

                if (stageCRJSON.dockerimage."${release.name}-image") {
                    if (stageCRJSON.dockerimage."${release.name}-image".image.startsWith("${script.env.dockerProxyRegistry}/${script.env.ciProject}/")) {
                        imageURL = stageCRJSON.dockerimage."${release.name}-image".image - "${script.env.dockerProxyRegistry}/${script.env.ciProject}/"
                    }
                    else {
                        imageURL = stageCRJSON.dockerimage."${release.name}-image".image - "${script.env.dockerProxyRegistry}/"
                    }
                    imageURL = imageURL.replaceAll(/(.*):.*/,'\$1')
                    helmfileYAML.releases[releaseIndex].values.add([image: [name: '{{ env "edpComponentDockerRegistryUrl" }}/{{ env "globalEDPProject" }}/' + imageURL, version: helmfileYAML.releases[releaseIndex].version]])
                }

                // version must be specified
                if (!release.version) {
                    throw new Exception("${release.name} version is not specified")
                }

                if (!script.fileExists("repositories/${repositoryPath}")) {
                    script.dir("repositories/${repositoryPath}") {
                        script.checkout([$class                           : 'GitSCM', branches: [[name: gitBranch]],
                                         doGenerateSubmoduleConfigurations: false, extensions: [],
                                         submoduleCfg                     : [],
                                         userRemoteConfigs                : [[credentialsId: gitCredentialsId,
                                                                              url          : release.labels.repoURL]]])
                        script.sh("rm -rf .git; rm -f .gitignore .helmignore; git init; git add --all; git commit -a -m 'Repo init'")
                        script.sh("git checkout -f -B ${release.version}")
                    }
                } else if (!script.sh(script: "cd repositories/${repositoryPath}; git branch -a | grep ${release.version} &2>1", returnStdout: true)) {
                    script.println "repositoryPath: ${repositoryPath}"
                    script.dir("${context.workDir}/tmp/${repositoryPath}") {
                        script.checkout([$class                           : 'GitSCM', branches: [[name: gitBranch]],
                                         doGenerateSubmoduleConfigurations: false, extensions: [],
                                         submoduleCfg                     : [],
                                         userRemoteConfigs                : [[credentialsId: gitCredentialsId,
                                                                              url          : release.labels.repoURL]]])
                        script.sh("rm -rf .git; rm -f .gitignore .helmignore")
                    }
                    script.dir("repositories/${repositoryPath}") {
                        script.sh("git checkout -f -B ${release.version}")
                        script.sh("mkdir -p ${context.workDir}/tmp/${release.name}_git; mv .git ${context.workDir}/tmp/${release.name}_git/; rm -rf ./*; mv ${context.workDir}/tmp/${release.name}_git/.git .git")
                        script.sh("scp -rp ${context.workDir}/tmp/${repositoryPath}/* ./")
                        script.sh("git add --all; git commit -a -m 'Added branch ${release.version}'")
                    }
                }
                else {
                    script.println "Repository repositories/${repositoryPath} and branch ${release.version} already exists"
                }
            }
            if (release.name == 'codebases' || release.name == 'registry-configuration') {
                helmfileYAML.releases[releaseIndex].values.add([codebases: [registryRegulations: [ registryRegulationsRepoVersion: stageCRJSON.gitsources.'empty-template-registry-regulation'.version]]])
                helmfileYAML.releases[releaseIndex].values.add([codebases: [registryRegulations: [ historyExcerptorRepoVersion: stageCRJSON.gitsources.'history-excerptor-chart'.version]]])
            }
            helmfileYAML.releases[releaseIndex].labels.remove('repoURL')
            helmfileYAML.releases[releaseIndex].labels.remove('stream')
            helmfileYAML.releases[releaseIndex].labels.branch = release.version
        }

        script.writeYaml data: helmfileYAML, file: helmfile, overwrite: true
        script.sh "cat ${helmfile}"
    }

    void run(context) {
        if (!script.fileExists("${context.workDir}/Dockerfile")) {
            script.error "[JENKINS][ERROR] There is no Dockerfile in the root directory of the project ${context.codebase.name}. "
        }

        def resultTag
        script.openshift.withCluster() {
            script.openshift.withProject() {
                script.dir("${context.workDir}") {
                    script.env.dockerRegistryHost = context.platform.getJsonPathValue("edpcomponent", "docker-registry", ".spec.url")
                    script.env.dockerProxyRegistry = context.job.dnsWildcard.startsWith("apps.cicd") ? 'nexus-docker-registry.' + context.job.dnsWildcard : ''
                    script.env.ciProject = context.job.dnsWildcard.startsWith("apps.cicd") ? 'mdtu-ddm-edp-cicd' : context.job.edpName

                    script.sh "git config --global user.email 'admin@example.com'"

                    if (!script.env.dockerRegistryHost) {
                        script.error("[JENKINS][ERROR] Couldn't get docker registry server")
                    }

                    def buildconfigName = "${context.codebase.name}-dockerfile-${context.git.branch.replaceAll("[^\\p{L}\\p{Nd}]+", "-")}"
                    def outputImagestreamName = "${context.codebase.name}-${context.git.branch.replaceAll("[^\\p{L}\\p{Nd}]+", "-")}"
                    def imageRepository = "${script.env.dockerRegistryHost}/${context.job.ciProject}/${outputImagestreamName}"

                    context.codebase.imageBuildArgs.push("--name=${buildconfigName}")

                    def imageUrl = "${imageRepository}:${context.codebase.isTag}"
                    context.codebase.imageBuildArgs.push("--to=${imageUrl}")
                    context.codebase.imageBuildArgs.push("--binary=true")
                    context.codebase.imageBuildArgs.push("--to-docker=true")
                    context.codebase.imageBuildArgs.push("--push-secret=nexus-docker-registry")

                    createOrUpdateBuildConfig(context.codebase, buildconfigName, imageUrl)

                    def repositoryPath, gitCredentialsId

                    stageCRJSON = script.readJSON(file: "properties/stageCR.json")

                    stageCRJSON.gitsources.each { component, value ->
                        repositoryPath = value.path.endsWith('/') || value.path == '' ? value.path + component + '.git' : value.path + '/' + component + '.git'
                        gitCredentialsId = value.repoURL.contains('gitbud.epam.com') ? 'git-epam-ciuser-sshkey' : 'gerrit-ciuser-sshkey'
                        script.dir("repositories/${repositoryPath}") {
                            script.checkout([$class                           : 'GitSCM', branches: [[name: value.version]],
                                             doGenerateSubmoduleConfigurations: false, extensions: [],
                                             submoduleCfg                     : [],
                                             userRemoteConfigs                : [[credentialsId: gitCredentialsId,
                                                                                  url          : value.repoURL]]])

                            script.sh("rm -rf .git; rm -f .gitignore .helmignore; git init; git add --all; git commit -a -m 'Repo init'")
                            script.sh("git checkout -f -B ${value.version}")
                        }
                    }


                    // template release components
                    ArrayList listOfTemplates = script.sh(script: """ ls -1 ${context.workDir}/resources/repositories/templates """, returnStdout: true).tokenize('\n')
                    listOfTemplates.each {
                        processHelmfile(context, "${context.workDir}/resources/repositories/templates/${it}/deploy-templates/helmfile.yaml")
                    }

                    // cluster-mgmt.git
                    processHelmfile(context, "${context.workDir}/resources/repositories/cluster-mgmt.git/properties/cluster-mgmt.yaml")
                    processHelmfile(context, "${context.workDir}/resources/repositories/cluster-mgmt.git/deploy-templates/helmfile.yaml")

                    // user-management.git
                    processHelmfile(context, "${context.workDir}/repositories/components/infra/user-management.git/deploy-templates/helmfile.yaml")

                    // external-integration-mocks.git
                    processHelmfile(context, "${context.workDir}/repositories/components/infra/external-integration-mocks.git/deploy-templates/helmfile.yaml")

                    // cluster-kafka-operator.git
                    processHelmfile(context, "${context.workDir}/repositories/components/infra/cluster-kafka-operator.git/deploy-templates/helmfile.yaml")

                    // create bare git repos from templates
                    script.dir("resources/repositories") {
                        def templateRepo = script.sh(script: "find ./ -name '*.git' -type d", returnStdout: true).tokenize('\n')
                        // TODO: adding datamock.git needed for registry
                        templateRepo.add('datamock.git')

                        templateRepo.each {
                            script.dir(it) {
                                script.sh "git init; git add --all; git commit -a --allow-empty -m 'Repo init'"
                                script.sh("git checkout -f -B ${context.codebase.version}")
                                script.sh("git checkout -f -B master")
                            }
                            script.sh "git clone --bare ${it} ${context.workDir}/git/${it}"
                        }
                    }

                    script.dir('repositories') {
                        script.sh(script: "find . -name .git -type d -prune -exec dirname {} \\;", returnStdout: true).tokenize('\n').each {
                            script.dir(it) {
                                script.sh("git commit --allow-empty -a -m 'updated properties'")
                                script.sh("git checkout -f -B master")
                            }
                            script.sh "git clone --bare ${it} ${context.workDir}/git/${it}"
                        }
                    }

                    script.sh "tar -cf ${context.codebase.name}.tar Dockerfile git/* resources/*"

                    def buildResult = script.openshift.selector(buildConfigApi, "${buildconfigName}").startBuild(
                            "--from-archive=${context.codebase.name}.tar",
                            "--wait=true")
                    resultTag = buildResult.object().status.output.to.imageDigest

                    script.println("[JENKINS][DEBUG] Build config ${context.codebase.name} with result " +
                            "${buildconfigName}:${resultTag} has been completed")

                    new CodebaseImageStreams(context, script)
                            .UpdateOrCreateCodebaseImageStream(outputImagestreamName, imageRepository, context.codebase.isTag)
                }
            }
        }
    }
}

return BuildDockerfileImageHelm
