// test/ssh.sh: the node "agent8-ssh", started by the SSH Build Agents launcher with Java Path
// set to the wrapper install.sh writes. JVM_OPTIONS is replaced by ssh.sh.
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import hudson.model.FreeStyleProject
import hudson.model.labels.LabelAtom
import hudson.plugins.sshslaves.SSHLauncher
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy
import hudson.slaves.DumbSlave
import hudson.tasks.Shell
import jenkins.model.Jenkins

def j = Jenkins.get()
def store = SystemCredentialsProvider.getInstance()
if (!store.credentials.any { it.id == 'agent8-ssh' }) {
    store.credentials.add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, 'agent8-ssh', 'agent8 test', 'jenkins', 'agent8-pw'))
    store.save()
}
def l = new SSHLauncher('localhost', 2222, 'agent8-ssh')
l.javaPath = '/opt/agent8/java'
l.jvmOptions = 'JVM_OPTIONS'
l.sshHostKeyVerificationStrategy = new NonVerifyingKeyVerificationStrategy()
l.maxNumRetries = 0
def n = new DumbSlave('agent8-ssh', '/home/jenkins/agent', l)
n.labelString = 'agent8-ssh'
n.numExecutors = 1
j.addNode(n)
def p = j.getItem('agent8-ssh-build') ?: j.createProject(FreeStyleProject, 'agent8-ssh-build')
p.assignedLabel = new LabelAtom('agent8-ssh')
p.buildersList.clear()
p.buildersList.add(new Shell('echo hello > a.txt; echo "read=$(cat a.txt)"'))
p.save()
println 'setup ok'
