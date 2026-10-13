// Creates the inbound node "agent8" and the test jobs. Run in the controller's script console.
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import hudson.model.ComputerSet
import hudson.plugins.git.GitTool
import org.jenkinsci.plugins.gitclient.JGitTool
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def j = Jenkins.get()
// The versioncolumn plugin's "JVM Version" monitor disconnects agents on an older Java than
// the controller; agent8 hosts need it set to keep them connected (see the README).
ComputerSet.monitors.findAll { it.class.name == 'hudson.plugin.versioncolumn.JVMVersionMonitor' }.each {
    it.disconnect = false
}
// Git checkouts on the agent use JGit (plugin code from the controller, run on old Java):
// the agent images have no git binary.
j.getDescriptorByType(GitTool.DescriptorImpl).setInstallations([new GitTool('Default', 'git', []), new JGitTool()] as GitTool[])
def store = SystemCredentialsProvider.instance
if (!store.credentials.any { it.id == 'agent8-cred' }) {
    store.credentials.add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, 'agent8-cred', '', 'agent8-user', 's3cret-pw'))
    store.save()
}
if (j.getNode('agent8') == null) {
    def n = new DumbSlave('agent8', '/srv/agent8', new JNLPLauncher(true))
    n.labelString = 'agent8'
    n.numExecutors = 2
    j.addNode(n)
}
def job = { name, script ->
    def p = j.getItem(name) ?: j.createProject(WorkflowJob, name)
    p.definition = new CpsFlowDefinition(script, true)
}
job('agent8-build', '''
node('agent8') {
  sh 'cat /etc/redhat-release 2>/dev/null || uname -sr; echo hello > a.txt'
  writeFile file: 'TEST-x.xml', text: '<testsuite name="s" tests="1"><testcase classname="c" name="t"/></testsuite>'
  junit 'TEST-*.xml'
  archiveArtifacts 'a.txt'
  stash name: 's', includes: 'a.txt'
  dir('sub') { unstash 's'; echo "read=${readFile('a.txt').trim()}" }
  echo "rc=${sh(script: 'exit 3', returnStatus: true)}"
  echo "out=${sh(script: 'echo remote-out', returnStdout: true).trim()}"
  withEnv(["GREETING=it's ok"]) { sh 'echo "greeting=$GREETING"' }
  echo "exists=${fileExists('a.txt')}"
}
''')
job('agent8-plugins', '''
node('agent8') {
  checkout([$class: 'GitSCM', branches: [[name: '*/main']], gitTool: 'jgit',
            userRemoteConfigs: [[url: 'file:///repo.git']]])
  sh 'echo "checked-out=$(cat README)"'
  withCredentials([usernamePassword(credentialsId: 'agent8-cred', usernameVariable: 'U', passwordVariable: 'P')]) {
    sh 'echo "user=$U"; test "$P" = s3cret-pw && echo password-ok'
  }
}
''')
job('agent8-abort', "node('agent8') { sh 'echo started; sleep 601 & sleep 600' }")
job('agent8-durable', "node('agent8') { sh 'sleep 30; echo survived' }")
println 'setup ok'
