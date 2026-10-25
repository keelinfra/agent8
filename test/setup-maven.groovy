// The Maven project type (maven-plugin): a job that runs Maven from /opt/maven on the
// "agent8" node. maven-plugin starts its own JVM on the agent for Maven, with its own
// remoting, so this checks that path on old Java. Run after setup.groovy.
import hudson.maven.MavenModuleSet
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.tasks.Maven
import jenkins.model.Jenkins

def j = Jenkins.get()
j.getDescriptorByType(Maven.DescriptorImpl).setInstallations(new Maven.MavenInstallation('maven', '/opt/maven', []))
def p = j.getItem('agent8-maven') ?: j.createProject(MavenModuleSet, 'agent8-maven')
p.assignedLabel = j.getLabel('agent8')
p.scm = new GitSCM(GitSCM.createRepoList('file:///repo.git', null), [new BranchSpec('*/main')], null, 'jgit', [])
p.setMaven('maven')
p.goals = '-B validate'
p.save()
println 'setup ok'
