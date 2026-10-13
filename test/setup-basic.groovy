// BASIC=1: a bare controller (core only). Creates the inbound node "agent8" and a freestyle
// job that runs a shell step on it and archives a file.
import hudson.model.FreeStyleProject
import hudson.model.labels.LabelAtom
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import hudson.tasks.ArtifactArchiver
import hudson.tasks.Shell
import jenkins.model.Jenkins

def j = Jenkins.get()
if (j.getNode('agent8') == null) {
    def n = new DumbSlave('agent8', '/srv/agent8', new JNLPLauncher(true))
    n.labelString = 'agent8'
    n.numExecutors = 2
    j.addNode(n)
}
def p = j.getItem('agent8-basic') ?: j.createProject(FreeStyleProject, 'agent8-basic')
p.assignedLabel = new LabelAtom('agent8')
p.buildersList.clear()
p.buildersList.add(new Shell('uname -sr; echo hello > a.txt; echo "read=$(cat a.txt)"; exit 0'))
p.publishersList.clear()
p.publishersList.add(new ArtifactArchiver('a.txt'))
p.save()
println 'setup ok'
