// The Maven project type (maven-plugin): jobs that run Maven from /opt/maven. maven-plugin
// starts its own JVM for Maven, with its own remoting. That JVM does not go through
// agent8's relaunch, so the agent8 job passes both Java agents itself. agent8-maven-builtin
// runs the same build on the controller's built-in node (Java 21, stock remoting) as a
// control. Run after setup.groovy.
import hudson.maven.MavenModuleSet
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.tasks.Maven
import jenkins.model.Jenkins

def j = Jenkins.get()
j.getDescriptorByType(Maven.DescriptorImpl).setInstallations(new Maven.MavenInstallation('maven', '/opt/maven', []))
def job = { name, label, opts ->
    def p = j.getItem(name) ?: j.createProject(MavenModuleSet, name)
    p.assignedLabel = j.getLabel(label)
    p.scm = new GitSCM(GitSCM.createRepoList('file:///repo.git', null), [new BranchSpec('*/main')], null, 'jgit', [])
    p.setMaven('maven')
    p.goals = '-B validate'
    p.mavenOpts = opts
    p.save()
}
job('agent8-maven', 'agent8', '-javaagent:/opt/jenkins/agent.jar -javaagent:/opt/jenkins/jvmdg.jar')
job('agent8-maven-builtin', 'built-in', null)
println 'setup ok'
