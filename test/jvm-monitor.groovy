// The "JVM Version" node monitor (versioncolumn plugin) takes agents offline whose Java is
// older than the controller's. agent8 hosts need it set to not disconnect (as setup.groovy
// does); run it now and report whether the agent stayed online.
import hudson.plugin.versioncolumn.JVMVersionMonitor
def d = Jenkins.get().getDescriptorByType(JVMVersionMonitor.JvmVersionDescriptor)
def t = d.triggerUpdate()
if (t != null) t.join()
def c = Jenkins.get().getComputer('agent8')
println "jvm=${d.get(c)} offline=${c.offline} cause=${c.offlineCause}"
