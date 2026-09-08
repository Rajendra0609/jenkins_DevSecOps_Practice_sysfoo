
def setupDockerAuth() {
    if (params.PUSH_IMAGES) {
        withCredentials([
            usernamePassword(credentialsId: "${env.DOCKER_HUB_CREDENTIALS_ID}",
                             usernameVariable: 'DOCKER_USER',
                             passwordVariable: 'DOCKER_PASS')
        ]) {
            sh '''
                mkdir -p "${WORKSPACE}/.docker-config"
                AUTH=$(printf '%s:%s' "$DOCKER_USER" "$DOCKER_PASS" | base64 | tr -d '\\n')
                cat > "${WORKSPACE}/.docker-config/config.json" <<EOF
{"auths":{"https://index.docker.io/v1/":{"auth":"${AUTH}"}}}
EOF
            '''
        }
    } else {
        echo 'ℹ️  PUSH_IMAGES=false — building only (Kaniko --no-push), no registry auth needed.'
    }
}


def collectReportArtifacts() {
    def files = sh(script: '''
        set -e
        find "$WORKSPACE" \\( -path "*/.git" -o -path "*/target" \\) -prune -o \\
            \\( -name 'gitleaks-report.json' -o -name 'trivy-*.json' -o -name 'zap-*.*' -o -name 'TEST-*.xml' \\) -print | sort
    ''', returnStdout: true).trim()
    if (!files) {
        return []
    }
    return files.split('\n').findAll { it?.trim() }.collect { it.trim() }
}

// ── Failure diagnostics ─────────────────────────────────────────────────
// markStageStart() runs as the first step of every stage. It records how
// many console-log lines existed *before* this stage began, so that if the
// stage fails we can slice out just that stage's own output later — not an
// arbitrary tail of the whole build, and not some other stage's log.
def markStageStart() {
    env.STAGE_LOG_START = currentBuild.rawBuild.getLog(5000).size().toString()
}

// captureStageFailure() is wired into every stage's post{failure{}}. It
// records which stage failed and slices the console log from that stage's
// start up to the point of failure. currentBuild.rawBuild talks to the
// Jenkins controller, not the agent workspace, so this works identically
// whether the stage ran on kube_xl or on a throwaway Kaniko/Trivy/ZAP pod.
def captureStageFailure() {
    env.FAILED_STAGE_NAME = env.STAGE_NAME ?: 'Unknown stage'
    def allLines = currentBuild.rawBuild.getLog(5000)
    def startIdx = 0
    try {
        startIdx = (env.STAGE_LOG_START ?: '0') as Integer
    } catch (ignored) {
        startIdx = 0
    }
    if (startIdx < 0 || startIdx > allLines.size()) {
        startIdx = 0
    }
    def stageLines = allLines.drop(startIdx)
    if (stageLines.size() > 150) {
        stageLines = stageLines.takeRight(150)
    }
    env.FAILED_STAGE_LOG = stageLines.join('\n')
}

def getTriggerInfo() {
    try {
        def causes = currentBuild.getBuildCauses()
        if (causes && causes.size() > 0) {
            def c = causes[0]
            return (c.userName ?: c.shortDescription ?: 'Unknown').toString()
        }
    } catch (e) {
        echo "Could not determine trigger: ${e.message}"
    }
    return 'Unknown'
}

def getCommitAuthors() {
    try {
        def authors = currentBuild.changeSets.collect { cs ->
            cs.items.collect { it.author.fullName }
        }.flatten().unique()
        return authors ? authors.join(', ') : 'No new commits (same as last build)'
    } catch (e) {
        echo "Could not determine commit author(s): ${e.message}"
        return 'Unknown'
    }
}

def isMasterBranch() {
    def branch = (env.BRANCH_NAME ?: params.GIT_BRANCH ?: '').replaceFirst(/^refs\/heads\//, '')
    return branch == 'master'
}

def getPullRequestNumber() {
    return (env.CHANGE_ID ?: params.GITHUB_PR_NUMBER ?: '').toString().trim()
}

def isMasterPullRequest() {
    def targetBranch = (env.CHANGE_TARGET ?: params.GITHUB_PR_TARGET_BRANCH ?: '').replaceFirst(/^refs\/heads\//, '')
    return targetBranch == 'master' && getPullRequestNumber()
}

pipeline {
    agent {
        label "kube_xl"
    }
    tools {
        maven 'maven'
    }
    triggers {
        pollSCM('H/5 * * * *')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 1, unit: 'HOURS')
        skipDefaultCheckout()
        timestamps()
        disableResume()
        retry(1)
        preserveStashes(buildCount: 10)
        quietPeriod(5)
    }

    parameters {
        string(name: 'GIT_BRANCH',                defaultValue: 'main1',                        description: 'Branch to build')
        string(name: 'GITHUB_REPO',               defaultValue: 'Rajendra0609/jenkins_DevSecOps_Practice_sysfoo',          description: 'CONFIRM ME — GitHub repository (owner/repo) for sysfoo')
        string(name: 'GIT_CHECKOUT_CREDENTIALS_ID', defaultValue: 'github',                      description: 'Credentials ID (username/password or PAT) used to git clone')
        string(name: 'GITHUB_CREDENTIALS_ID',     defaultValue: 'GITHUB_TOKEN',                  description: 'Secret-text credentials ID for GitHub API calls (Preflight Checks reachability check). Leave blank to skip that check.')
        string(name: 'GITHUB_PR_NUMBER',          defaultValue: '',                              description: 'Optional pull request number to merge after a successful validation. Multibranch builds use CHANGE_ID automatically.')
        string(name: 'GITHUB_PR_TARGET_BRANCH',   defaultValue: 'master',                       description: 'Pull request target branch allowed for automatic merge')

        string(name: 'DOCKERHUB_NAMESPACE',       defaultValue: 'daggu1997',                     description: 'CONFIRM ME — Docker Hub namespace/org the image is pushed under')
        string(name: 'VERSION',                   defaultValue: 'v0.1.0',                        description: 'CONFIRM ME — Docker image version tag; keep in sync with pom.xml <version>')
        string(name: 'DOCKER_HUB_CREDENTIALS_ID', defaultValue: 'docker',                        description: 'Credentials ID for Docker Hub (username/password)')
        booleanParam(name: 'PUSH_IMAGES',         defaultValue: true,                             description: 'Push the built image to Docker Hub. Uncheck for a build-only / PR-validation run (Kaniko --no-push).')

        string(name: 'EMAIL_RECIPIENTS',          defaultValue: 'rajendra.daggubati09@gmail.com,srirajendraprasaddaggubati@gmail.com', description: 'Comma-separated email recipients')

        // ── DevSecOps scan toggles ──────────────────────────────────────────
        string(name: 'SONAR_PROJECT_KEY',         defaultValue: 'sysfoo',                        description: 'SonarQube project key (project must exist / be auto-provisioned on the server)')
        booleanParam(name: 'ABORT_ON_QUALITY_GATE', defaultValue: false,                          description: 'Fail the whole build if the SonarQube Quality Gate is red')
        string(name: 'NEXUS_URL',                 defaultValue: '6a9d20625a7bd38c0b951474-fa7f5e.node-ap-c7ae.iximiuz.com',             description: 'Nexus host name without protocol')
        string(name: 'NEXUS_REPOSITORY',         defaultValue: 'sysfoo',                      description: 'Nexus hosted repository for WAR files')
        string(name: 'NEXUS_CREDENTIALS_ID',     defaultValue: 'nexus',             description: 'Jenkins username/password credentials ID for Nexus')
        booleanParam(name: 'RUN_TRIVY_SCAN',      defaultValue: true,                             description: 'Scan the pushed image with Trivy. Only runs when PUSH_IMAGES is also true.')
        string(name: 'TRIVY_SEVERITY',            defaultValue: 'CRITICAL,HIGH',                  description: 'Comma-separated severities Trivy should report on')
        string(name: 'ZAP_TARGET_URL',            defaultValue: '',                               description: 'URL of a running (staging) instance to DAST-scan with OWASP ZAP baseline. Leave blank to skip — this pipeline does not deploy, so there is usually nothing to point ZAP at until you run it manually somewhere.')
        string(name: 'NEXUS_ARTIFACT_VERSION',   defaultValue: '',                               description: 'Optional Nexus version. Blank publishes pom version with Jenkins build number to avoid release redeployment conflicts.')
    }

    environment {
        SRC_ROOT      = "${WORKSPACE}/source"

        DOCKER_HUB_CREDENTIALS_ID = "${params.DOCKER_HUB_CREDENTIALS_ID}"
        DOCKER_IMAGE              = "${params.DOCKERHUB_NAMESPACE}/sysfoo"

        GITHUB_REPO    = "${params.GITHUB_REPO}"
        GITHUB_API_URL = 'https://api.github.com'

        EMAIL_RECIPIENTS = "${params.EMAIL_RECIPIENTS}"

        TERM = 'xterm-256color'
    }

    stages {
        // ── 1. Checkout ─────────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                script { markStageStart() }
                echo '🔄 Cloning repo into source/'
                checkout scm: [
                    $class: 'GitSCM',
                    branches: [[name: "${params.GIT_BRANCH}"]],
                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'source']],
                    userRemoteConfigs: [[
                        url          : "https://github.com/${params.GITHUB_REPO}.git",
                        credentialsId: "${params.GIT_CHECKOUT_CREDENTIALS_ID}",
                        name         : 'origin'
                    ]]
                ]
                script {
                    env.GIT_SHA = (env.GIT_COMMIT ?: sh(script: 'cd source && git rev-parse HEAD', returnStdout: true).trim()).take(8)
                    echo "📌 Building from commit ${env.GIT_SHA}"
                }

                // Single Maven module — no per-service split like TFGen's
                // backend/frontend/database, so this stashes the whole
                // checkout (minus build output) for the Kaniko stage below.
                stash(
                    name: 'docker-build-context',
                    includes: 'source/**',
                    excludes: '**/target/**, **/.git/**'
                )
            }
            post {
                always {
                    archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
                }
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 2. Preflight Checks ────────────────────────────────────────────.
        stage('Check Node Status') {
            steps {
                script { markStageStart() }
                withCredentials([
                    string(credentialsId: "${params.GITHUB_CREDENTIALS_ID}", variable: 'GITHUB_TOKEN'),
                    usernamePassword(credentialsId: "${env.DOCKER_HUB_CREDENTIALS_ID}",
                                     usernameVariable: 'DOCKER_USER',
                                     passwordVariable: 'DOCKER_PASS')
                ]) {
                    script {
                        def status = sh(script: 'bash /usr/local/bin/node_status.sh', returnStdout: true).trim()
                        echo "Node Status:\n${status}"
                        if (status.contains("ERROR") || status.contains("DOWN")) {
                            error("Node status check failed: ${status}")
                        }
                    }
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 3. Gitleaks Scan ──────────────────────────────────────────────────
        stage('Gitleaks Scan') {
            steps {
                script { markStageStart() }
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    dir('source') {
                        echo '🔍 Running Gitleaks secret scan...'
                        script {
                            def exitCode = sh(
                                script: '''
                                    gitleaks detect --source . --redact --report-format=json --report-path=../gitleaks-report.json --exit-code 1
                                ''',
                                returnStatus: true
                            )
                            def reportPath = "${WORKSPACE}/gitleaks-report.json"
                            if (fileExists(reportPath)) {
                                def reportText = readFile(reportPath).trim()
                                if (reportText != '' && reportText != 'null') {
                                    def leaks = readJSON file: reportPath
                                    if (leaks && leaks.size() > 0) {
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                        echo "🚨 Gitleaks found ${leaks.size()} potential secret(s):"
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                        leaks.eachWithIndex { leak, i ->
                                            echo """
[${i + 1}] Rule      : ${leak.RuleID}
    File      : ${leak.File}
    Line      : ${leak.StartLine}
    Commit    : ${leak.Commit}
    Author    : ${leak.Author}
    Secret    : ${leak.Secret}   ← REDACTED in report (--redact flag is on)
"""
                                        }
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                        echo "ℹ️  False positives? Add them to source/.gitleaksignore"
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                    }
                                }
                            }
                            if (exitCode != 0) {
                                echo "🚨 Gitleaks found secrets in the repo. Continuing pipeline as allowed failure."
                            }
                        }
                    }
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 5. Maven Lifecycle ─────────────────────────────────────────────
        stage('Maven Lifecycle') {
            steps {
                script { markStageStart() }
                dir('source') {
                    echo '🔧 Running Maven lifecycle: clean -> validate -> compile'
                    sh 'mvn -B -ntp clean validate compile'
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 6. Run Tests ────────────────────────────────────────────────────
        stage('Run Tests') {
            steps {
                script { markStageStart() }
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    dir('source') {
                        echo '🧪 Running Maven test phase...'
                        sh 'mvn -B -ntp test'
                    }
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'source/target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'source/target/surefire-reports/*.xml', allowEmptyArchive: true
                }
                failure {
                    script { captureStageFailure() }
                }
            }
        }
        stage('SonarQube Analysis') {
            steps {
                script { markStageStart() }
                dir('source') {
                    echo '📊 Running SonarQube static analysis...'
                    withEnv([
                            "SONAR_SCANNER_OPTS=-Xmx1024m",
                            "SONAR_SCANNER_JAVA_OPTS=-Xmx2048m -XX:+ExitOnOutOfMemoryError"
                        ]) {
                        withSonarQubeEnv('sonar') {
                            sh """
                                                                mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                                                                    -Dsonar.projectKey=${params.SONAR_PROJECT_KEY} \
                                                                    -Dsonar.projectName=${params.SONAR_PROJECT_KEY} \
                                                                    -Dsonar.sources=src/main/java \
                                                                    -Dsonar.exclusions=**/node_modules/**,**/dist/**,**/build/**,**/*.test.js,**/*.spec.js,**/*.test.jsx,**/database/** \
                                                                    -Dsonar.sourceEncoding=UTF-8 \
                                                                    -Dsonar.qualitygate.wait=true \
                                                                    -Dsonar.qualitygate.timeout=300
                                                                test -f target/sonar/report-task.txt
                            """
                        }
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'source/target/sonar/report-task.txt', allowEmptyArchive: true
                }
                failure {
                    script { captureStageFailure() }
                }
            }
        }
        stage('Quality Gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: params.ABORT_ON_QUALITY_GATE
                }
            }
        }

        // ── 8. Build WAR ───────────────────────────────────────────────────
        stage('Build WAR') {
            steps {
                script { markStageStart() }
                dir('source') {
                    echo '📦 Building the WAR package...'
                    echo '🏗️  Running Maven package phase for the production WAR...'
                    sh 'mvn -B -ntp -DskipTests package'
                    script {
                        def warFiles = findFiles glob: 'target/*.war'
                        if (!warFiles) {
                            error('No WAR file was produced in source/target')
                        }
                        if (warFiles.size() > 1) {
                            error("Expected one WAR file, found: ${warFiles*.path.join(', ')}")
                        }
                        def pom = readMavenPom file: 'pom.xml'
                        writeFile file: 'build-report.txt', text: """project=${pom.groupId}:${pom.artifactId}
version=${pom.version}
war=${warFiles[0].path}
status=SUCCESS
"""
                        echo "✅ Built ${warFiles[0].path}"
                    }
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: 'source/build-report.txt, source/target/*.war', allowEmptyArchive: true
                }
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 8. Upload war to Nexus ────────────────────────────────────
        stage('Upload WAR to Nexus') {
            steps {
                script { markStageStart() }
                dir('source') {
                    script {
                        def pom = readMavenPom file: 'pom.xml'
                        def warFiles = findFiles glob: 'target/*.war'
                        if (!warFiles) {
                            error('No WAR file was produced in source/target')
                        }
                        if (warFiles.size() > 1) {
                            error("Expected one WAR file, found: ${warFiles*.path.join(', ')}")
                        }
                        if (!fileExists(warFiles[0].path)) {
                            error("WAR file is missing: ${warFiles[0].path}")
                        }

                        def nexusBase    = params.NEXUS_URL.replaceAll('/+$', '')
                        def nexusVersion = params.NEXUS_ARTIFACT_VERSION?.trim() ?: "${pom.version}-${env.BUILD_NUMBER}"
                        def groupPath    = pom.groupId.replace('.', '/')
                        def fileName     = "${pom.artifactId}-${nexusVersion}.war"
                        def uploadUrl    = "${nexusBase}/repository/${params.NEXUS_REPOSITORY}/${groupPath}/${pom.artifactId}/${nexusVersion}/${fileName}"

                        echo "⬆️  Uploading ${warFiles[0].path} to ${uploadUrl}"

                        withCredentials([usernamePassword(
                            credentialsId: params.NEXUS_CREDENTIALS_ID,
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS'
                        )]) {
                            def httpCode = sh(
                                script: """
                                    curl -sS -o /tmp/nexus_upload_response.txt -w '%{http_code}' \\
                                        -u "\$NEXUS_USER:\$NEXUS_PASS" \\
                                        --upload-file "${warFiles[0].path}" \\
                                        "${uploadUrl}"
                                """,
                                returnStdout: true
                            ).trim()

                            echo "Nexus responded with HTTP ${httpCode}"
                            sh 'cat /tmp/nexus_upload_response.txt || true'

                            if (!(httpCode ==~ /2\d\d/)) {
                                error("Upload to Nexus failed with HTTP ${httpCode} — see response body above for the real reason")
                            }
                        }

                        echo "✅ Uploaded ${fileName} to ${params.NEXUS_REPOSITORY}"
                    }
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 6. Build & Push Image (Kaniko) ─────────────────────────────────
        // TFGen built three images (backend/frontend/database) in three
        // separate Kaniko stages. Sysfoo produces one image from the
        // Dockerfile at the repo root, so this is a single stage.
        stage('Build & Push Image') {
            when {
                expression { isMasterBranch() }
            }
            agent {
                kubernetes {
                    label 'kaniko'
                }
            }
            environment {
                BUILD_DIR = "${WORKSPACE}/source"
                PATH      = "/busybox:/kaniko:$PATH"
            }
            steps {
                script { markStageStart() }
                unstash 'docker-build-context'
                script { setupDockerAuth() }
                withEnv(["DOCKER_CONFIG=${WORKSPACE}/.docker-config"]) {
                    script {
                        def pushFlag = params.PUSH_IMAGES ? '' : '--no-push'
                        echo "🐳 Building ${env.DOCKER_IMAGE}:${params.VERSION}"
                        sh """
                            /kaniko/executor \
                              --context=dir://${env.BUILD_DIR} \
                              --dockerfile=${env.BUILD_DIR}/Dockerfile \
                              --destination=${env.DOCKER_IMAGE}:${params.VERSION} \
                              --destination=${env.DOCKER_IMAGE}:${env.GIT_SHA} \
                              ${pushFlag} \
                              --verbosity=info
                        """
                    }
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 7. Trivy Image Scan ─────────────────────────────────────────────
        stage('Trivy Image Scan') {
            when {
                expression { isMasterBranch() && params.PUSH_IMAGES && params.RUN_TRIVY_SCAN }
            }
            agent {
                kubernetes {
                    label 'kube_trivy'
                    yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: trivy
      image: aquasec/trivy:latest
      imagePullPolicy: Always
      command: ['cat']
      tty: true
"""
                }
            }
            steps {
                script { markStageStart() }
                container('trivy') {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        withCredentials([
                            usernamePassword(credentialsId: "${env.DOCKER_HUB_CREDENTIALS_ID}",
                                             usernameVariable: 'TRIVY_USERNAME',
                                             passwordVariable: 'TRIVY_PASSWORD')
                        ]) {
                            script {
                                def img = "${env.DOCKER_IMAGE}:${params.VERSION}"
                                def safeName = img.replaceAll('[/:]', '_')
                                echo "🔎 Scanning ${img} with Trivy"
                                def exitCode = sh(
                                    script: """
                                        trivy image \
                                          --severity ${params.TRIVY_SEVERITY} \
                                          --exit-code 1 \
                                          --timeout 10m \
                                          --format json \
                                          --output trivy-${safeName}.json \
                                          ${img}
                                    """,
                                    returnStatus: true
                                )
                                sh "trivy image --severity ${params.TRIVY_SEVERITY} --format table ${img} || true"
                                // BUG FIX (carried over from the source pipeline): that version
                                // looped over three images and set `failed = false` here — a typo
                                // that meant the `if (failed) { error(...) }` check after the loop
                                // could never fire, so Trivy findings never actually failed the
                                // build no matter how severe. There's only one image here, so the
                                // loop/flag is gone too — this just fails (well, marks UNSTABLE,
                                // via the catchError() wrapping this whole block) as soon as
                                // Trivy's own exit code says it found something.
                                if (exitCode != 0) {
                                    echo "🚨 Trivy found ${params.TRIVY_SEVERITY} findings in ${img}"
                                    error("Trivy found vulnerabilities at or above configured severity — see archived report")
                                }
                            }
                        }
                    }
                    archiveArtifacts artifacts: 'trivy-*.json', allowEmptyArchive: true
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 8. OWASP ZAP Baseline Scan ──────────────────────────────────────
        // Only runs if you explicitly set ZAP_TARGET_URL — there's no
        // deploy stage in this pipeline, so nothing is auto-provisioned for
        // ZAP to point at. Point it at wherever you've manually run the
        // image (e.g. your home-lab instance) when you want a DAST pass.
        stage('OWASP ZAP Baseline Scan') {
            when {
                expression { isMasterBranch() && params.ZAP_TARGET_URL?.trim() }
            }
            agent {
                kubernetes {
                    label 'kube_zap'
                    yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: zap
      image: zaproxy/zap-stable:latest
      imagePullPolicy: Always
      command:
        - cat
      tty: true
      volumeMounts:
        - name: zap-work
          mountPath: /zap/wrk

  volumes:
    - name: zap-work
      emptyDir: {}
"""
                }
            }
            steps {
                script { markStageStart() }
                container('zap') {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        echo "🕷️  Running OWASP ZAP baseline scan against ${params.ZAP_TARGET_URL}"
                        sh """
                            cd /zap/wrk

                            export PATH="/zap:\$PATH"

                            python3 /zap/zap-full-scan.py \
                                -t "${params.ZAP_TARGET_URL}" \
                                -r zap-full-report.html \
                                -J zap-full-report.json \
                                -m 30 \
                                -a \
                                -I
                        """
                    }
                    archiveArtifacts artifacts: 'zap-*.*', allowEmptyArchive: true
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }

        // ── 9. Merge validated pull request ────────────────────────────────
        stage('Merge Pull Request') {
            when {
                allOf {
                    expression { isMasterPullRequest() }
                    expression { currentBuild.currentResult == 'SUCCESS' }
                }
            }
            steps {
                script { markStageStart() }
                withCredentials([string(credentialsId: params.GITHUB_CREDENTIALS_ID, variable: 'GITHUB_TOKEN')]) {
                    script {
                        def prNumber = getPullRequestNumber()
                        def mergeUrl = "${env.GITHUB_API_URL}/repos/${params.GITHUB_REPO}/pulls/${prNumber}/merge"
                        def response = sh(
                            script: """
                                curl -sS -w '\\n%{http_code}' -X PUT \\
                                    -H 'Accept: application/vnd.github+json' \\
                                    -H 'Authorization: Bearer \\$GITHUB_TOKEN' \\
                                    -H 'X-GitHub-Api-Version: 2022-11-28' \\
                                    -H 'Content-Type: application/json' \\
                                    -d '{"merge_method":"squash"}' \\
                                    '${mergeUrl}'
                            """,
                            returnStdout: true
                        ).trim().split('\n')
                        def httpCode = response[-1]
                        def body = response[0..-2].join('\n')
                        echo "GitHub merge response: HTTP ${httpCode}"
                        echo body
                        if (!(httpCode ==~ /20[01]/)) {
                            error("Pull request #${prNumber} was not merged")
                        }
                    }
                }
            }
            post {
                failure {
                    script { captureStageFailure() }
                }
            }
        }
    }

    // ── Post Actions ──────────────────────────────────────────────────────────
    post {
        always {
            archiveArtifacts artifacts: 'gitleaks-report.json, trivy-*.json, zap-*.*, source/build-report.txt, source/target/surefire-reports/*.xml, source/target/sonar/report-task.txt', allowEmptyArchive: true
            script {
                try {
                    cleanWs()
                    echo '🧹 Workspace cleaned'
                } catch (Exception e) {
                    echo "ℹ️ Workspace cleanup skipped: ${e.message}"
                }
            }
        }

        success {
            script {
                def triggeredBy     = getTriggerInfo()
                def commitAuthors   = getCommitAuthors()
                def commitId        = env.GIT_SHA ?: 'N/A'
                def commitUrl       = env.GIT_SHA ? "https://github.com/${params.GITHUB_REPO}/commit/${env.GIT_SHA}" : ''
                def reportArtifacts = collectReportArtifacts().collect { it.replace("${WORKSPACE}/", '') }
                def reportSummary   = reportArtifacts ? reportArtifacts.join('\n') : 'No report artifacts were generated.'

                slackSend(
                    channel: '#doc_jen_task_tracker',
                    message: """
                        ✅ *Pipeline Success*
                        *Job:* `${env.JOB_NAME}`
                        *Build #:* `${env.BUILD_NUMBER}`
                        *Branch:* `${params.GIT_BRANCH}`
                        *Commit:* `${commitId}`${commitUrl ? " (<${commitUrl}|view>)" : ''}
                        *Committer(s):* ${commitAuthors}
                        *Triggered by:* ${triggeredBy}
                        *Image:* `${env.DOCKER_IMAGE}:${params.VERSION}`
                        *Status:* Passed ✅
                        *Reports:* `${reportSummary.replace('\n', ' | ')}`
                        <${env.BUILD_URL}|View Build Logs>
                    """
                )
                mail(
                    to: "${env.EMAIL_RECIPIENTS}",
                    subject: "SUCCESS: ${env.JOB_NAME} [#${env.BUILD_NUMBER}]",
                    body: """\
                        The Jenkins Pipeline completed successfully.

                        🌿 Branch      : ${params.GIT_BRANCH}
                        🔑 Commit      : ${commitId}${commitUrl ? " (${commitUrl})" : ''}
                        👤 Committer(s): ${commitAuthors}
                        🚀 Triggered by: ${triggeredBy}

                        🐳 Image : ${env.DOCKER_IMAGE}:${params.VERSION} (${commitId})
                        🔗 Pipeline URL : ${env.BUILD_URL}

                        📦 Report artifacts archived for this build:
                        ${reportSummary}
                    """
                )
            }
        }

        failure {
            script {
                def triggeredBy     = getTriggerInfo()
                def commitAuthors   = getCommitAuthors()
                def commitId        = env.GIT_SHA ?: 'N/A (failed before/at checkout)'
                def commitUrl       = env.GIT_SHA ? "https://github.com/${params.GITHUB_REPO}/commit/${env.GIT_SHA}" : ''
                def failedStage     = env.FAILED_STAGE_NAME ?: 'Unknown (see full console log)'
                def failedStageLog  = env.FAILED_STAGE_LOG ?: currentBuild.rawBuild.getLog(50).join('\n')
                def reportArtifacts = collectReportArtifacts().collect { it.replace("${WORKSPACE}/", '') }
                def reportSummary   = reportArtifacts ? reportArtifacts.join('\n') : 'No report artifacts were generated.'

                slackSend(
                    channel: '#doc_jen_task_tracker',
                    message: """
                        ❌ *Pipeline Failure*
                        *Job:* `${env.JOB_NAME}`
                        *Build #:* `${env.BUILD_NUMBER}`
                        *Branch:* `${params.GIT_BRANCH}`
                        *Commit:* `${commitId}`${commitUrl ? " (<${commitUrl}|view>)" : ''}
                        *Committer(s):* ${commitAuthors}
                        *Triggered by:* ${triggeredBy}
                        *Failed Stage:* `${failedStage}`
                        *Status:* FAILED ❌
                        *Reports:* `${reportSummary.replace('\n', ' | ')}`
                        <${env.BUILD_URL}|View Build Logs>
                    """
                )
                mail(
                    to: "${env.EMAIL_RECIPIENTS}",
                    subject: "FAILURE: ${env.JOB_NAME} [#${env.BUILD_NUMBER}] — failed at ${failedStage}",
                    body: """\
                        The Jenkins Pipeline has FAILED ❌

                        🌿 Branch       : ${params.GIT_BRANCH}
                        🔑 Commit       : ${commitId}${commitUrl ? " (${commitUrl})" : ''}
                        👤 Committer(s) : ${commitAuthors}
                        🚀 Triggered by : ${triggeredBy}
                        🧱 Failed Stage : ${failedStage}
                        🔗 Pipeline URL : ${env.BUILD_URL}

                        📦 Report artifacts available in the build:
                        ${reportSummary}

                        📄 Log for the failed stage (${failedStage}):
                        --------------------------------------------------
                        ${failedStageLog}
                        --------------------------------------------------
                    """
                )
            }
        }

        unstable {
            script {
                def triggeredBy     = getTriggerInfo()
                def commitAuthors   = getCommitAuthors()
                def commitId        = env.GIT_SHA ?: 'N/A'
                def commitUrl       = env.GIT_SHA ? "https://github.com/${params.GITHUB_REPO}/commit/${env.GIT_SHA}" : ''
                def failedStage     = env.FAILED_STAGE_NAME ?: 'Unknown (see full console log)'
                def failedStageLog  = env.FAILED_STAGE_LOG ?: currentBuild.rawBuild.getLog(50).join('\n')
                def reportArtifacts = collectReportArtifacts().collect { it.replace("${WORKSPACE}/", '') }
                def reportSummary   = reportArtifacts ? reportArtifacts.join('\n') : 'No report artifacts were generated.'

                slackSend(
                    channel: '#doc_jen_task_tracker',
                    message: """
                        ⚠️ *Pipeline Unstable*
                        *Job:* `${env.JOB_NAME}`
                        *Build #:* `${env.BUILD_NUMBER}`
                        *Branch:* `${params.GIT_BRANCH}`
                        *Commit:* `${commitId}`${commitUrl ? " (<${commitUrl}|view>)" : ''}
                        *Committer(s):* ${commitAuthors}
                        *Triggered by:* ${triggeredBy}
                        *Flagged Stage:* `${failedStage}`
                        *Status:* UNSTABLE ⚠️
                        *Reports:* `${reportSummary.replace('\n', ' | ')}`
                        <${env.BUILD_URL}|View Build Logs>
                    """
                )
                mail(
                    to: "${env.EMAIL_RECIPIENTS}",
                    subject: "UNSTABLE: ${env.JOB_NAME} [#${env.BUILD_NUMBER}] — flagged at ${failedStage}",
                    body: """\
                        The Jenkins Pipeline is UNSTABLE ⚠️

                        🌿 Branch        : ${params.GIT_BRANCH}
                        🔑 Commit        : ${commitId}${commitUrl ? " (${commitUrl})" : ''}
                        👤 Committer(s)  : ${commitAuthors}
                        🚀 Triggered by  : ${triggeredBy}
                        🧱 Flagged Stage : ${failedStage}
                        🔗 Pipeline URL  : ${env.BUILD_URL}

                        📦 Report artifacts available in the build:
                        ${reportSummary}

                        📄 Log for the flagged stage (${failedStage}):
                        --------------------------------------------------
                        ${failedStageLog}
                        --------------------------------------------------
                    """
                )
            }
        }
    }
}
