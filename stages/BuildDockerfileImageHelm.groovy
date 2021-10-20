package com.epam.edp.stages.impl.ci.impl.builddockerfileimage

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.epam.edp.stages.impl.ci.impl.codebaseiamgestream.CodebaseImageStreams

@Stage(name = "build-image-from-dockerfile", buildTool = ["gitops"], type = [ProjectType.APPLICATION])
class BuildDockerfileImageHelm {
    Script script

    def buildConfigApi = "buildconfig"

    void createOrUpdateBuildConfig(codebase, buildConfigName, imageUrl) {
        if (!script.openshift.selector(buildConfigApi, "${buildConfigName}").exists()) {
            script.openshift.newBuild(codebase.imageBuildArgs)
            return
        }
        script.sh("oc patch --type=merge ${buildConfigApi} ${buildConfigName} -p \"{\\\"spec\\\":{\\\"output\\\":{\\\"to\\\":{\\\"name\\\":\\\"${imageUrl}\\\"}}}}\" ")
    }

    void processHelmfile(context, def helmfile) {
        def helmfileYAML = script.readYaml file: helmfile
        def repositoryPath, gitCredentialsId
        String helmfileDirectory = script.sh(script: "dirname -z ${helmfile}", returnStdout: true).trim()

        helmfileYAML.releases.eachWithIndex { release, releaseIndex ->
            if (release.labels.type == 'remote') {
                def gitBranch = (release.version == 'master' || release.labels.isbranch == true) ? release.version : 'build/' + release.version
                def branch
                repositoryPath = release.labels.path.endsWith('/') || release.labels.path == '' ? release.labels.path + release.name + '.git' : release.labels.path + '/' + release.name + '.git'
                gitCredentialsId = release.labels.repoURL.contains('gitbud.epam.com') ? 'git-epam-ciuser-sshkey' : 'gerrit-ciuser-sshkey'
                
                helmfileYAML.releases[releaseIndex].values.add([image: [name: '{{ env "edpComponentDockerRegistryUrl" }}/{{ env "globalEDPProject" }}/' + release.name + '-' + release.labels.stream, version: helmfileYAML.releases[releaseIndex].version]])
                helmfileYAML.releases[releaseIndex].remove('repoURL')
                helmfileYAML.releases[releaseIndex].remove('stream')
                helmfileYAML.releases[releaseIndex].remove('type')

                if (!script.fileExists("repositories/${repositoryPath}")) {
                    script.dir("repositories/${repositoryPath}") {
                        // version must be specified
                        if (!release.version) {
                            throw new Exception("${release.name} version is not specified")
                        }

                        script.checkout([$class                           : 'GitSCM', branches: [[name: gitBranch]],
                                         doGenerateSubmoduleConfigurations: false, extensions: [],
                                         submoduleCfg                     : [],
                                         userRemoteConfigs                : [[credentialsId: gitCredentialsId,
                                                                              url          : release.labels.repoURL]]])

                        try {
                            script.sh "cp -r deploy-templates/values.yaml ${helmfileDirectory}/${release.name}_values.yaml"
                        }
                        catch (e) {
                            script.writeFile file: "${helmfileDirectory}/${release.name}_values.yaml", text: "# Values for ${release.name}"
                            script.println "${e}"
                        }

                        if (release.version.matches("^[0-9]+\\.[0-9]+\\.[0-9]+.*")) {
                            def chartYaml = script.readYaml file: 'deploy-templates/Chart.yaml'
                            chartYaml.version = release.version
                            script.writeYaml data: chartYaml, file: 'deploy-templates/Chart.yaml', overwrite: true
                        }

                        try {
                            script.sh "cp -r deploy-templates/values.gotmpl ${helmfileDirectory}/${release.name}_values.gotmpl"
                        }
                        catch (e) {
                            script.writeFile file: "${helmfileDirectory}/${release.name}_values.gotmpl", text: "# Values with variables for ${release.name}"
                            script.println "${e}"
                        }

                        if (release.version == 'master') {
                            branch = release.version
                        } else if (release.labels.isbranch == true) {
                            branch = release.version
                        } else {
                            branch = release.version.replaceAll(/([0-9]+\.[0-9]+\.[0-9]+).*/, "\$1")
                        }

                        script.sh("rm -rf .git; rm -f .gitignore .helmignore; git init; git add --all; git commit -a -m 'Repo init'")
                        script.sh("git checkout -f -B ${branch}")
                        script.sh("git checkout -f -B master")
                    }
                    script.sh "git clone --bare ${context.workDir}/repositories/${repositoryPath} ${context.workDir}/git/${repositoryPath}"
                }
                else {
                    script.println "repositories/${repositoryPath} already exists"
                }
            }
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

                    def gitsourcesJSON = script.readJSON(file: "properties/gitsources.json")
                    gitsourcesJSON.each { component, value ->
                        repositoryPath = value.path.endsWith('/') || value.path == '' ? value.path + component + '.git' : value.path + '/' + component + '.git'
                        gitCredentialsId = value.url.contains('gitbud.epam.com') ? 'git-epam-ciuser-sshkey' : 'gerrit-ciuser-sshkey'
                        script.dir("repositories/${repositoryPath}") {
                            script.checkout([$class                           : 'GitSCM', branches: [[name: value.version]],
                                             doGenerateSubmoduleConfigurations: false, extensions: [],
                                             submoduleCfg                     : [],
                                             userRemoteConfigs                : [[credentialsId: gitCredentialsId,
                                                                                  url          : value.url]]])
                            def branch
                            if (value.version.startsWith('build/')) {
                                branch = value.version.replaceAll(/build\/([0-9]+\.[0-9]+\.[0-9]+).*/, "\$1")
                            } else if (value.version.startsWith('release/')) {
                                branch = value.version.replaceAll(/release\/([0-9]+\.[0-9]+).*/, "\$1")
                            }
                            else {
                                branch = value.version
                            }

                            script.sh("rm -rf .git; rm -f .gitignore .helmignore; git init; git add --all; git commit -a -m 'Repo init'")
                            script.sh("git checkout -f -B ${branch}")
                            script.sh("git checkout -f -B master")

                            script.sh "git clone --bare ${context.workDir}/repositories/${repositoryPath} ${context.workDir}/git/${repositoryPath}"
                        }
                    }


                    // template release components
                    processHelmfile(context, "${context.workDir}/resources/repositories/templates/registry-tenant-template-small.git/deploy-templates/helmfile.yaml")
                    processHelmfile(context, "${context.workDir}/resources/repositories/templates/registry-tenant-template-medium.git/deploy-templates/helmfile.yaml")
                    processHelmfile(context, "${context.workDir}/resources/repositories/templates/registry-tenant-template-large.git/deploy-templates/helmfile.yaml")

                    // cluster-mgmt.git
                    processHelmfile(context, "${context.workDir}/resources/repositories/cluster-mgmt.git/properties/cluster-mgmt.yaml")
                    processHelmfile(context, "${context.workDir}/resources/repositories/cluster-mgmt.git/deploy-templates/helmfile.yaml")

                    // user-management.git
                    processHelmfile(context, "${context.workDir}/repositories/components/infra/user-management.git/deploy-templates/helmfile.yaml")

                    // create bare git repos from templates
                    script.dir("resources/repositories") {
                        def templateRepo = script.sh(script: "find ./ -name '*.git' -type d", returnStdout: true).tokenize('\n')
                        // TODO: adding datamock.git needed for registry
                        templateRepo.add('datamock.git')

                        templateRepo.each {
                            script.sh "mkdir -p ${it}; cd ${it}; git init; git add --all; git commit -a --allow-empty -m 'Repo init'"
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
