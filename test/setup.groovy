// Creates the inbound node "agent8" and the test jobs. Run in the controller's script console.
import hudson.model.ComputerSet
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
job('agent8-abort', "node('agent8') { sh 'echo started; sleep 601 & sleep 600' }")
job('agent8-durable', "node('agent8') { sh 'sleep 30; echo survived' }")
println 'setup ok'
