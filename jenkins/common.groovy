import groovy.json.JsonOutput


class InnerJobContext implements Serializable {
    String commit
    String baseCommit = ''  // Base commit to merge with (Will Be Removing)
    String baseBranch = ''  // Base branch to merge with
    String baseCommitID = ''
    Integer pr_id = 0
    String owner = 'jenkins'

    // defaults (minutes) used to calculate default keepWsTimeout - prevent disk cleanup
    Integer timeoutCloneMin = 60;
    Integer timeoutBuildMin = 60;
    Integer timeoutTriageMin = 10;

    Boolean notifyGithubStatus = false
    Boolean notifySlack = false
    String nodeLabel = 'pr_agent_small_rhel8'
    String slackChannel = '#jenkins'
    Boolean keepOnSuccess = true
}

enum GitHubStatus implements Serializable {PENDING, FAILURE, SUCCESS, TIMEOUT}

enum TimeoutUnits implements Serializable {DAYS, HOURS, MINUTES}

// Groovy/Jenkins enums don't seem to be accessible through a 'load'
// defining methods to allow pipelines to access them with 'common.method()'
// TODO: investigate if these can be removed, or moved into InnerJobContext
def enumGitHubStatusPending() {
  return GitHubStatus.PENDING
}
def enumGitHubStatusFailure() {
  return GitHubStatus.FAILURE
}
def enumGitHubStatusSuccess() {
  return GitHubStatus.SUCCESS
}

def enumGitHubStatusTimeout() {
  return GitHubStatus.TIMEOUT
}


def enumTimeoutUnitsDays() {
  return TimeoutUnits.PENDING
}
def enumTimeoutUnitsHours() {
  return TimeoutUnits.HOURS
}
def enumTimeoutUnitsMinutes() {
  return TimeoutUnits.MINUTES
}

// Global variables
// Groovy/Jenkins sandbox scoping rules are strange and functions defined in
// this file cannot reference variables declared at the top level, but they can
// reference classes and static class variables defined at the top level.
class G {
    static String GITHUB_SSH_CREDENTIALS_ID = 'ad25d95a-4a30-4cc9-bda9-e836d51af0b9'
    static String GITHUB_OAUTH_TOKEN_CREDENTIALS_ID = 'bab094ff-ed07-4f37-ab33-67803368e526'
    static String GITHUB_REPO = 'rahul-sinha-sifive/test-yaml-job'

    // Set a common WCKEY for all slurm jobs launched by this
    // pipeline. This won't capture everything because some jobs still
    // run on the slurm agent and thus cannot be readily tracked.
    // The slurm account is set in the run_job function so it is
    // specific to the job being run.
    //
    // The 'scd_' prefix is David's suggestion to differentiate
    // scd jobs from other potential users of slurm who want
    // to also use UUID in this same fashion.
    static String SLURM_WCKEY = 'scd_' + UUID.randomUUID().toString()
}

def get_ssh_creds() {
    return G.GITHUB_SSH_CREDENTIALS_ID
}

// Set 'context.timeoutBuildMin' to the given timeoutParam after converting to an integer, if not set use the
// given timeoutDefault.  The units specified by 'paramUnits' are used to convert the timeoutParam to ninutes
def initTimeoutParams(InnerJobContext context, String timeoutParam, String paramName, int timeoutDefault, TimeoutUnits paramUnits) {
    int mult = 1 // MINUTES
    if (paramUnits == TimeoutUnits.HOURS) {
        mult = 60
    } else if (paramUnits == TimeoutUnits.DAYS) {
        mult = 24 * 60
    }
    if (timeoutParam) {
        context.timeoutBuildMin = timeoutParam.toInteger() * mult
        println "Note: timeoutBuildMin=${context.timeoutBuildMin} minutes from ${paramName} ${timeoutParam}"
    } else {
        context.timeoutBuildMin = timeoutDefault * mult
        println "Note: timeoutBuildMin=${context.timeoutBuildMin} minutes from default ${timeoutDefault} ${paramUnits}"
    }
}

def scdCheckout(String commitish, String baseCommit, InnerJobContext context, env) {
    deleteDir()
    int retries = 0
    int max_attempts = 3
    // Retry clone operation if it fails
    while(retries++ < max_attempts) {
        try {
            checkout([
                $class: 'GitSCM',
                branches: [[name: commitish]],
                extensions: [[$class: 'CloneOption',
                    // reference: '/sifive/github-cache/federation.git',
                    timeout: context.timeoutCloneMin,
                ]],
                userRemoteConfigs: [[
                    credentialsId: G.GITHUB_SSH_CREDENTIALS_ID,
                    url: "git@github.com:${G.GITHUB_REPO}.git",
                ]],
            ])
            if (baseCommit != '') {
                context.baseCommitID = sh(script: "git rev-parse ${baseCommit}", returnStdout: true)
                sh "git checkout --detach && git merge ${baseCommit}"
            }
            return
        } catch (e) {
            println "Clone Error Caught, will retry a total of [${max_attempts}] times, [${e}]"
            sleep 10
        }
    }
    if (context.notifySlack) { notifySlack("SCD Repo Checkout", env, context.slackChannel) }
    throw new Exception("SCD Checkout Failure, See Errors Above")
}

// TODO: Figure out why Jenkins GitHub plugin isn't working
def sendGitHubStatus(GitHubStatus status, String commit, env, String target_url = env.BUILD_URL) {
    def description
    def state
    switch (status) {
        case GitHubStatus.PENDING:
            description = 'Pending Jenkins build'
            state = 'pending'
            break;
        case GitHubStatus.FAILURE:
            description = 'Jenkins build failed'
            state = 'failure'
            break;
        case GitHubStatus.SUCCESS:
            description = 'Jenkins build succeeded'
            state = 'success'
            break;
        case GitHubStatus.TIMEOUT:
            description = 'Jenkins build timed out'
            state = 'failure'
            break;
        default:
            throw new Exception("Invalid status: ${status}")
    }
    def requestBody = JsonOutput.toJson([
        state: state,
        description: description,
        target_url: target_url,
        context: env.JOB_NAME.split('/')[-1],
    ])
    def requestURL = "https://api.github.com/repos/${G.GITHUB_REPO}/statuses/${commit}"
    withCredentials([[
        $class: 'StringBinding',
        credentialsId: G.GITHUB_OAUTH_TOKEN_CREDENTIALS_ID,
        variable: 'GITHUB_OAUTH_TOKEN',
    ]]) {
        sh """curl --silent --show-error --fail -H "Authorization: token \$GITHUB_OAUTH_TOKEN" -H 'Content-Type: application/json' -X POST '${requestURL}' -d '${requestBody}'"""
    }
}

def recordPrId(theBuild, prId, hasHtml=true) {
    def prUrl = "https://github.com/${G.GITHUB_REPO}/pull/${prId}"
    def updatedLine = "PR: ${prUrl}"
    def sep = '\n'
    if (hasHtml) {
        updatedLine = """<b>PR:</b> <a href="${prUrl}">${prUrl}</a>"""
        sep = "\n<br>"
    }
    def updatedDesc = ""
    if (theBuild.description?.trim()) {
        updatedDesc = theBuild.description
    }
    if (!updatedDesc.contains(prUrl)) {
        if (!updatedDesc?.trim()) {
            updatedDesc += sep
        }
        updatedDesc += updatedLine
        theBuild.setDescription(updatedDesc)
    }
}

def setDisplayName(theBuild, context, status) {
    def baseBranch = env.DISPLAY_BRANCH ?: (context.baseBranch ?: 'Master' )
    theBuild.displayName = env.DISPLAY_NAME ? "${env.BUILD_NUMBER} - ${env.DISPLAY_NAME}" :
        "${env.BUILD_NUMBER} - Branch: ${baseBranch} PR: ${context.pr_id} Owner: ${context.owner} - ${status}"
}

def notifySlack(subJobName, env, channel = '#jenkins') {
    slackSend channel: channel, color: '#FF0000', message: "FAILED on ${env.NODE_NAME}: Job '${env.JOB_NAME} ${subJobName} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})"
}


def copyReleaseTarballs() {
    if (params.COPY_RELEASE_TARBALLS_PATH) {
        def destdir = "${params.COPY_RELEASE_TARBALLS_PATH}/${env.JOB_NAME}/${env.BUILD_NUMBER}"
        def result = sh (
            label: "Find and copy release tarballs to ${destdir}",
            script: """set -x; mkdir -p "${destdir}" && /usr/bin/env FIND_COPY_DEST_PATH="${destdir}" jenkins/release-tarball-find-copy.sh""",
            returnStatus: true,
        )
        if (result != 0) {
            println("Find and copy release tarballs: ignored failure result=${result}")
        }
    } else {
        println("Find and copy release tarballs: not configured")
    }
}


def killDuplicateBuilds() {
    stage('Killing Duplicate Builds') {
        node('tiny') {
            killDuplicateBuildsNoStage()
        }
    }
}

def killDuplicateBuildsNoStage() {
    if (!env.PR_ID) { return }
    def currentBuildPRID = env.PR_ID.toInteger();

    try {
        def job = Jenkins.instance.getItemByFullName(env.JOB_NAME.trim());
        def found = 0;
        def builds_to_go_back = 50;
        def builds_checked = 0
        for (build in job.builds) {
            // Due to a bug where a build that does not exist is found, we limit how many jobs we go back
            if (builds_checked > builds_to_go_back) {
                println("No Previous Running Build for PR [${currentBuildPRID}]");
                return 0
            }
            builds_checked++;
            def previousBuildNumber = build.number;
            if(previousBuildNumber < env.BUILD_NUMBER.toInteger() && build.isBuilding()) {
                def parameters = build.getAction(hudson.model.ParametersAction)
                def previousBuildPRID = parameters.getParameter("PR_ID").value.toInteger()
                if(previousBuildPRID == currentBuildPRID && currentBuildPRID != 0) {
                    println("Trying to Abort Previous Running Build #${previousBuildNumber} for PR [${currentBuildPRID}]")
                    found = 1;
                    build.doStop();
                    println("Previous Running Build #${previousBuildNumber} Successfully Aborted")
                }
            }
        }
        if (!found) {
            println("No Previous Running Build for PR [${currentBuildPRID}]");
        }
    } catch (e) {
        echo "Issue with Killing Duplicate build"
    }
}


def killJenkinsAgent() {
    try {
        timeout(time: 2, unit: 'MINUTES') {
            println('Killing Jenkins Agent (IGNORE CANNOT CONTACT MESSAGE BELOW)')
            sh(label: 'Killing Jenkins Agent', script: "/sifive/tools/sifive/jenkins-utils/tools/kill-jenkins-agent-process.py")
        }
    } catch (kill_err) {
        println('Killing Straggling Jenkins Job Finished Successfully')
    }
}

def killSlurmJobs(env) {
    try {
        env.SLURM_WCKEY = G.SLURM_WCKEY
        sh(label: 'Killing Slurm Jobs',  script: '/sifive/tools/sifive/jenkins-utils/tools/kill-jobs-by-wckey.sh')
    } catch (e) {
        echo "Slurm Job Killer Failed [$e]"
    }
}

// node: node to disconnect https://javadoc.jenkins-ci.org/hudson/model/Node.html
// message: message printed in jenkins node UI to explain why it was marked offline
// logPrefix: prepended string to the begining of all log messages, can be left as an empty string. Helpful to regex grouped messages
def markSpecificNodeOffline(node, message, logPrefix) {
    def computer = node.toComputer()
    if (computer.countExecutors() == 1) {
        print("${logPrefix}[${node.getNodeName()}] Marking node offline: ${message}")
        computer.setTemporarilyOffline(true, new hudson.slaves.OfflineCause.ByCLI(message))
    } else {
        print("${logPrefix}[${node.getNodeName()}] Not marking node offline, as executors != 1. Executors: ${computer.countExecutors()}")
    }
}

// node: node to disconnect https://javadoc.jenkins-ci.org/hudson/model/Node.html
// message: message printed in jenkins node UI to explain why it was marked online (I personally haven't worked out where this message is displayed)
// logPrefix: prepended string to the begining of all log messages, can be left as an empty string. Helpful to regex grouped messages
def markSpecificNodeOnline(node, message, logPrefix) {
    def computer = node.toComputer()
    // Node should have exactly 1 executor, however this method is safe to
    // call on nodes without 1 executor so we shall just print a warning
    print("${logPrefix}[${node.getNodeName()}] Marking node online. Executors: ${computer.countExecutors()}")
    computer.setTemporarilyOffline(false, new hudson.slaves.OfflineCause.ByCLI(message))
}

// node: node to disconnect https://javadoc.jenkins-ci.org/hudson/model/Node.html
// message: message printed in jenkins node UI to explain why it was disconnected
// logPrefix: prepended string to the begining of all log messages, can be left as an empty string. Helpful to regex grouped messages 
def disconnectSpecificNode(node, message, logPrefix) {
    def computer = node.toComputer()
    if (computer.countExecutors() == 1) {
        print("${logPrefix}[${node.getNodeName()}] Disconnect/Kill Node with exactly 1 executor")
        computer.cliDisconnect(message)
    } else {
        print("${logPrefix}[${node.getNodeName()}] Not disconnecting node with ${computer.countExecutors()} executors as this would be unsafe")
    }
}

def triageResults(env) {
    def summary_en = 1
    def ret = null
    try {
        timeout(time: 10, unit: 'MINUTES') {
            echo "Running failure_finder"
            ret = sh(label: 'Triage', script: '/sifive/tools/sifive/triage-tools/latest/bin/failure_finder $BUILD_URL --html', returnStdout: true)
        }
    } catch (e) {
        echo "No Triage Results, Not Printing results on Build Page"
        summary_en = 0
    }

    if (summary_en) {
        echo "Writing summary"
        def summary = manager.createSummary("warning.gif")
        summary.appendText(ret, false)
    }
}

def printJobDetails() {
    def host = sh(script: 'set +x; echo $HOSTNAME', returnStdout: true).trim()
    println """\
        Jenkins Job Details:
            HOSTNAME: ${host}
            SLURM_JOBID: ${env.JENKINS_SLURM_JOBID}
            SLURM_WCKEY: ${G.SLURM_WCKEY}
            WORKSPACE: ${env.WORKSPACE}
            PR_ID: ${env.PR_ID}
            PR_OWNER: ${env.PR_OWNER}
    """.stripIndent()
}

def printSCDDetails(context) {
    println """\
        SCD Git Details:
            COMMIT: ${context.commit}
            BASE_COMMIT: ${context.baseCommit}
            BASE_BRANCH: ${context.baseBranch}
            BASE_COMMIT_ID: ${context.baseCommitID}
    """.stripIndent()
}

def keepWorkspace(InnerJobContext context) {
    // preserve workspace from cleaner for a decent default (sum of all the timeouts)
    // or as directed by KEEP_WS_HOURS parameter

    def workspace = env.WORKSPACE
    int keepMin = context.timeoutCloneMin + context.timeoutBuildMin + context.timeoutTriageMin
    String reason = "Projected ${keepMin}m of total build time"

    if (params.KEEP_WS_HOURS) {
        keepMin = context.timeoutCloneMin + (60 * params.KEEP_WS_HOURS.toInteger()) + context.timeoutTriageMin
        reason = "Requested ${keepMin}m from KEEP_WS_HOURS=${params.KEEP_WS_HOURS}"
    }
    def cmd = "/sifive/tools/sifive/jenkins-utils/disk-cleaner/disk-cleaner-keep-dir.sh"
    def result = sh (
        label: "keepWorkspace: ${reason}: ${workspace}",
        script: """set -x; ${cmd} --flush --owner=${context.owner} --reason='${reason} for ${env.BUILD_URL}' --keep=${keepMin}m ${workspace}""",
        returnStatus: true,
    )
    if (result != 0) {
        println("keepWorkspace: WARNING: 'disk-cleaner-keep-dir.sh' failed, result=${result}: workspace may not be protected")
    }
}


def workspaceCleanup(Boolean keepOnSuccess) {
    def buildResult = currentBuild.currentResult
    def workspace = env.WORKSPACE
    if (buildResult != 'SUCCESS') {
        println("Keeping (result=${buildResult}) workspace: ${workspace}")
    } else if (keepOnSuccess) {
        println("Keeping (result=${buildResult} and KEEP_ON_SUCCESS=true) workspace: ${workspace}")
    } else {
        println("Removing (result=${buildResult} and KEEP_ON_SUCCESS=false) workspace: ${workspace}")
        cleanWs()
    }
}

def prependTimeCmd(String cmd){
    return "/usr/bin/env time -f 'time_cmd stats:\n    Elapsed Wall Time(h:m:s): %E\n    MaxRSS(kbytes): %M\n    CPU Percentage: %P\n' ${cmd}"
}

/** Wrapper around empty constructor for InnerJobContext
 *
 * Jenkins does not add shared library classes to the class path, so
 * Jenkinsfiles which load this script are unable to reference classes declared
 * here.
 */
def JobContext() {
    new InnerJobContext()
}


return this
