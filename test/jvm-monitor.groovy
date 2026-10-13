// The "JVM Version" node monitor (versioncolumn plugin) takes agents offline whose Java is
// older than the controller's, unless set to "ignore". Run it now and report the result.
import hudson.plugin.versioncolumn.JVMVersionMonitor
def d = Jenkins.get().getDescriptorByType(JVMVersionMonitor.JvmVersionDescriptor)
def t = d.triggerUpdate()
if (t != null) t.join()
def c = Jenkins.get().getComputer('agent8')
println "jvm=${d.get(c)} offline=${c.offline} cause=${c.offlineCause}"
