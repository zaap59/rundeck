/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.services

import com.dtolabs.rundeck.app.support.ProjectArchiveImportRequest
import com.dtolabs.rundeck.app.support.ProjectArchiveParams
import com.dtolabs.rundeck.core.authorization.AuthContextEvaluator
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.authorization.Validation
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.FrameworkProjectMgr
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.common.ProjectManager
import com.dtolabs.rundeck.util.ZipBuilder
import grails.events.bus.EventBus
import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import grails.testing.web.GrailsWebUnitTest
import org.grails.spring.beans.factory.InstanceFactoryBean
import org.rundeck.app.components.project.ProjectComponent
import rundeck.BaseReport
import rundeck.CommandExec
import rundeck.ExecReport
import rundeck.Execution
import rundeck.Project
import rundeck.ScheduledExecution
import rundeck.Workflow
import rundeck.codecs.JobsXMLCodec
import rundeck.services.authorization.PoliciesValidation
import spock.lang.Specification
import spock.lang.Unroll
import webhooks.WebhookService
import webhooks.component.project.WebhooksProjectComponent
import webhooks.importer.WebhooksProjectImporter

import java.util.jar.JarOutputStream
import java.util.zip.ZipOutputStream

/**
 * Created by greg on 8/5/15.
 */
class ProjectServiceSpec extends Specification implements ServiceUnitTest<ProjectService>, GrailsWebUnitTest, DataTest {

    def setup() {
        mockDomain Project
        mockDomain BaseReport
        mockDomain ExecReport
        mockDomain ScheduledExecution
        mockDomain Execution
        mockDomain CommandExec
        mockCodec JobsXMLCodec
    }

    def "loadJobFileRecord"() {
        given:
        def ofileuuid = UUID.randomUUID().toString()
        def ojobid = UUID.randomUUID().toString()
        def newjobid = UUID.randomUUID().toString()
        def oldexecid = '123'

        ScheduledExecution se = new ScheduledExecution(jobName: 'blue', project: 'AProject', adhocExecution: true,
                                                       uuid: newjobid,
                                                       adhocFilepath: '/this/is/a/path', groupPath: 'some/where',
                                                       description: 'a job', argString: '-a b -c d',
                                                       workflow: new Workflow(
                                                               keepgoing: true,
                                                               commands: [new CommandExec(
                                                                       [adhocRemoteString: 'test buddy', argString:
                                                                               '-delay 12 -monkey cheese -particle']
                                                               )]
                                                       ),
                                                       ).save()

        Execution exec = new Execution(
                scheduledExecution: se,
                argString: "-ftest1 $ofileuuid",
                user: "testuser",
                project: "AProject",
                loglevel: 'WARN',
                doNodedispatch: true,
                dateStarted: new Date(0),
                dateCompleted: new Date(3600000),
                nodeInclude: 'test1',
                nodeExcludeTags: 'monkey',
                status: 'true',
                workflow: new Workflow(commands: [new CommandExec(adhocRemoteString: 'exec command')])
        ).save()

        def xml = """
<jobFileRecord>
  <execId>$oldexecid</execId>
  <uuid>$ofileuuid</uuid>
  <recordName>ftest1</recordName>
  <jobId>$ojobid</jobId>
  <fileName>resource.yaml</fileName>
  <sha>071bbe64581d4c33737af25b61ac3d612c3dcdf9d3e869fde77e66e16112daba</sha>
  <size>352</size>
  <dateCreated>2017-02-28T00:17:41Z</dateCreated>
  <lastUpdated>2017-02-28T00:17:42Z</lastUpdated>
  <expirationDate>2017-02-28T00:18:11Z</expirationDate>
  <user>admin</user>
  <fileState>deleted</fileState>
  <storageReference>211ace71-df1c-4b3d-b351-40b8ac007cb9</storageReference>
  <storageType>filesystem-temp</storageType>
  <storageMeta />
  <serverNodeUUID>3425B691-7319-4EEE-8425-F053C628B4BA</serverNodeUUID>
  <recordType>option</recordType>
</jobFileRecord>"""

        when:
        def result = service.loadJobFileRecord(xml.toString(), [123: exec.id], [(ojobid): se.extid])
        def newfileid = result.uuid
        then:
        result != null
        result.jobId == newjobid
        result.execution.id == exec.id
        newfileid != ofileuuid
        result.execution.argString == "-ftest1 $newfileid".toString()

    }
    def "importProjectConfig"(){
        given:
        def tempfile = File.createTempFile("test-importProjectConfig",".properties")
        def origprops = [a:'b',x:'%PROJECT_BASEDIR%/sub/path/file.txt'] as Properties
        tempfile.withOutputStream {
            origprops.store(it,'test')
        }
        tempfile.deleteOnExit()
        def dbproj = new Project(name:  'myproject')
        dbproj.save(flush: true)
        def project = Mock(IRundeckProject){
            getName()>>'myproject'
        }
        def fwk = Mock(Framework){
            1 * getFrameworkProjectsBaseDir() >> new File('/projects/dir')
        }

        when:
        service.importProjectConfig(tempfile,project,fwk)

        then:
        1 * project.setProjectProperties([a:'b',x:'/projects/dir/myproject/sub/path/file.txt'] as Properties)
    }
    def "importProjectMdFiles"(){
        given:
        def tempfile1 = File.createTempFile("test-importProjectConfig1",".md")
        tempfile1.text='file1'
        tempfile1.deleteOnExit()
        def tempfile2 = File.createTempFile("test-importProjectConfig1",".md")
        tempfile2.text='file2'
        tempfile2.deleteOnExit()
        def mdfiles=[
                'readme.md':tempfile1,
                'motd.md':tempfile2
        ]
        def project = Mock(IRundeckProject){
            getName()>>'myproject'

        }

        when:
        service.importProjectMdFiles(mdfiles,project)

        then:
        1 * project.storeFileResource('readme.md',{it.text=='file1'})
        1 * project.storeFileResource('motd.md',{it.text=='file2'})
    }
    def "importProjectACLPolicies valid"(){
        given:
        def tempfile1 = File.createTempFile("test-importProjectConfig1",".md")
        tempfile1.text='file1'
        tempfile1.deleteOnExit()
        def tempfile2 = File.createTempFile("test-importProjectConfig1",".md")
        tempfile2.text='file2'
        tempfile2.deleteOnExit()
        def policyfiles=[
                'test.aclpolicy':tempfile1,
                'test2.aclpolicy':tempfile2
        ]
        def project = Mock(IRundeckProject){
            getName()>>'myproject'
        }
        service.authorizationService=Mock(AuthorizationService){
            1 * validateYamlPolicy('myproject','files/acls/test.aclpolicy',_) >> Mock(PoliciesValidation){
                isValid()>>true
            }
            1 * validateYamlPolicy('myproject','files/acls/test2.aclpolicy',_) >> Mock(PoliciesValidation){
                isValid()>>true
            }
            0 * _(*_)
        }

        when:
        def result=service.importProjectACLPolicies(policyfiles,project)

        then:
        result==[]
        1 * project.storeFileResource('acls/test.aclpolicy',{it.text=='file1'})
        1 * project.storeFileResource('acls/test2.aclpolicy',{it.text=='file2'})
    }
    def "importProjectACLPolicies invalid"(){
        given:
        def tempfile1 = File.createTempFile("test-importProjectConfig1",".md")
        tempfile1.text='file1'
        tempfile1.deleteOnExit()
        def tempfile2 = File.createTempFile("test-importProjectConfig1",".md")
        tempfile2.text='file2'
        tempfile2.deleteOnExit()
        def policyfiles=[
                'test.aclpolicy':tempfile1,
                'test2.aclpolicy':tempfile2
        ]
        def project = Mock(IRundeckProject){
            getName()>>'myproject'
        }
        service.authorizationService=Mock(AuthorizationService){
            1 * validateYamlPolicy('myproject','files/acls/test.aclpolicy',_) >> Mock(PoliciesValidation){
                isValid()>>false
                getErrors()>>['blah':['blah']]
                toString()>>'test validation failure'
            }
            1 * validateYamlPolicy('myproject','files/acls/test2.aclpolicy',_) >> Mock(PoliciesValidation){
                isValid()>>true
            }
            0 * _(*_)
        }

        when:
        def result=service.importProjectACLPolicies(policyfiles,project)

        then:
        0 * project.storeFileResource('acls/test.aclpolicy',{it.text=='file1'})
        1 * project.storeFileResource('acls/test2.aclpolicy',{it.text=='file2'})
        result==['files/acls/test.aclpolicy: test validation failure']
    }

    def "replacePlaceholderForProjectProperties"(){
        given:
        def project = Mock(IRundeckProject){
            getName()>>'myproject'
        }
        def fwk = Mock(Framework){
            1 * getFrameworkProjectsBaseDir() >> new File(dir)
        }

        def props = [x: before]

        when:
        def result = service.replacePlaceholderForProjectProperties(project, fwk, props, placeholder)

        then:
        result!=null
        result.x==after

        where:
        placeholder         | dir      | before                                | after
        '%PROJECT_BASEDIR%' | '/a/dir' | '/sub/path/file.txt'                  | '/sub/path/file.txt'
        '%PROJECT_BASEDIR%' | '/a/dir' | '%PROJECT_BASEDIR%/sub/path/file.txt' | '/a/dir/myproject/sub/path/file.txt'
        '%PROJECT_BASEDIR%' | '/a/dir' | '/sub/path/%PROJECT_BASEDIR%file.txt' | '/sub/path/%PROJECT_BASEDIR%file.txt'
        '%PROJECT_BASEDIR%' | '/a/dir' | '/sub/path/file.txt%PROJECT_BASEDIR%' | '/sub/path/file.txt%PROJECT_BASEDIR%'
    }

    def "replaceRelativePathsForProjectProperties"(){
        given:
        def project = Mock(IRundeckProject){
            getName()>>'myproject'
        }
        def fwk = Mock(Framework){
            1 * getFrameworkProjectsBaseDir() >> new File(dir)
        }

        def props = [x: before]

        when:
        def result = service.replaceRelativePathsForProjectProperties(project, fwk, props, placeholder)

        then:
        result!=null
        result.x==after

        where:
        placeholder         | dir      | before                               | after
        '%PROJECT_BASEDIR%' | '/a/dir' | '/sub/path/file.txt'                 | '/sub/path/file.txt'
        '%PROJECT_BASEDIR%' | '/a/dir' | '/a/dir/myproject/sub/path/file.txt' | '%PROJECT_BASEDIR%/sub/path/file.txt'
        '%PROJECT_BASEDIR%' | '/a/dir' | '/b/a/dir/sub/path/file.txt'         | '/b/a/dir/sub/path/file.txt'
    }

    def "delete project disables scm plugins"() {
        given:
        def project = Mock(IRundeckProject) {
            getName() >> 'myproject'
        }
        service.scmService = Mock(ScmService)
        service.executionService = Mock(ExecutionService)
        service.fileUploadService = Mock(FileUploadService)

        def fwk = Mock(Framework)

        when:
        def result = service.deleteProject(project, fwk, null, null)


        then:
        1 * service.scmService.removeAllPluginConfiguration('myproject')
        1 * service.executionService.deleteBulkExecutionIds(*_)
        1 * fwk.getFrameworkProjectMgr() >> Mock(ProjectManager) {
            1 * removeFrameworkProject('myproject')
        }
        1 * service.fileUploadService.deleteRecordsForProject('myproject')
        result.success

    }

    def "delete project notifies event bus success"() {
        given:
            def project = Mock(IRundeckProject) {
                getName() >> 'myproject'
            }
            service.scmService = Mock(ScmService)
            service.executionService = Mock(ExecutionService)
            service.fileUploadService = Mock(FileUploadService)
            service.targetEventBus = Mock(EventBus)
            def fwk = Mock(Framework)

        when:
            def result = service.deleteProject(project, fwk, null, null)

        then:
            1 * service.eventBus.notify('projectWillBeDeleted', ['myproject'])
            1 * service.eventBus.notify('projectWasDeleted', ['myproject'])
            1 * fwk.getFrameworkProjectMgr() >> Mock(ProjectManager) {
                1 * removeFrameworkProject('myproject')
            }
            result.success
    }
    def "delete project notifies event bus failure"() {
        given:
            def project = Mock(IRundeckProject) {
                getName() >> 'myproject'
            }
            service.scmService = Mock(ScmService)
            service.executionService = Mock(ExecutionService)
            service.fileUploadService = Mock(FileUploadService){
                deleteRecordsForProject(_)>>{throw new Exception("test exception")}
            }
            service.targetEventBus = Mock(EventBus)
            def fwk = Mock(Framework)

        when:
            def result = service.deleteProject(project, fwk, null, null)

        then:
            1 * service.eventBus.notify('projectWillBeDeleted', ['myproject'])
            1 * service.eventBus.notify('projectDeleteFailed', ['myproject'])
            0 * fwk.getFrameworkProjectMgr()
            !result.success
    }
    def "delete project calls component projectDelete"() {
        given:
        ProjectComponent component1 = Mock(ProjectComponent){
            getName()>>'comp1'
        }
        ProjectComponent component2 = Mock(ProjectComponent){
            getName()>>'comp2'
        }
        defineBeans {
            testProjectComponent(InstanceFactoryBean, component1, ProjectComponent)
            testProjectComponent2(InstanceFactoryBean, component2, ProjectComponent)
        }

        def project = Mock(IRundeckProject) {
            getName() >> 'myproject'
        }
        service.scmService = Mock(ScmService)
        service.executionService = Mock(ExecutionService)
        service.fileUploadService = Mock(FileUploadService)
        service.targetEventBus = Mock(EventBus)

        def prjMgr = Mock(ProjectManager) {
            removeFrameworkProject(_) >> {}
        }
        def fwk = Mock(Framework) {
            getFrameworkProjectMgr() >> { prjMgr }
        }

        when:
        service.deleteProject(project, fwk, null, null)

        then:
        1 * component1.projectDeleted('myproject')
        1 * component2.projectDeleted('myproject')
    }

    def "import project archive does not fail when webhooks are enabled but project archive has no webhook defs"() {
        setup:

        ProjectComponent component = Mock(ProjectComponent){
            getName()>>'webhooks'
            getImportFilePatterns()>>['webhooks.yaml']
        }
        defineBeans {
            testProjectComponent(InstanceFactoryBean, component, ProjectComponent)
        }
        def project = Mock(IRundeckProject) {
            getName() >> 'importtest'
        }
        def framework = Mock(Framework) {
            getFrameworkProjectsBaseDir() >> { File.createTempDir() }
        }
        def authCtx = Mock(UserAndRolesAuthContext) {
            getUsername() >> {"user"}
            getRoles() >> {["admin"] as Set}
        }
        service.scheduledExecutionService = Mock(ScheduledExecutionService) {
            loadJobs(_,_,_,_,_,_) >> { [] }
            issueJobChangeEvent(_) >> {}
        }
        service.logFileStorageService = Mock(LogFileStorageService) {
            getFileForExecutionFiletype(_,_,_,_) >> { File.createTempFile("import","import") }
        }
        service.rundeckAuthContextEvaluator=Mock(AuthContextEvaluator){

        }

        ProjectArchiveImportRequest rq = Mock(ProjectArchiveImportRequest){
            getProject()>>'importtest'
            getImportConfig()>>true
            getImportACL()>>true
            getImportScm()>>true
            getImportComponents()>>[webhooks:true]
        }

        when:
        def result = service.importToProject(project,framework,authCtx, getClass().getClassLoader().getResourceAsStream("test-rdproject.jar"),rq)

        then:
        result
        0 * component.doImport(*_)
    }

    def "import project archive with project components none enabled"() {
        setup:

            ProjectComponent component = Mock(ProjectComponent) {
                getName() >> 'webhooks'
                getImportFilePatterns() >> ['webhooks.yaml']
            }
            defineBeans {
                testProjectComponent(InstanceFactoryBean, component, ProjectComponent)
            }
            def project = Mock(IRundeckProject) {
                getName() >> 'importtest'
            }
            def framework = Mock(Framework) {
                getFrameworkProjectsBaseDir() >> { File.createTempDir() }
            }
            def authCtx = Mock(UserAndRolesAuthContext) {
                getUsername() >> { "user" }
                getRoles() >> { ["admin"] as Set }
            }
            service.scheduledExecutionService = Mock(ScheduledExecutionService) {
                loadJobs(_, _, _, _, _, _) >> { [] }
                issueJobChangeEvent(_) >> {}
            }
            service.logFileStorageService = Mock(LogFileStorageService) {
                getFileForExecutionFiletype(_, _, _, _) >> { File.createTempFile("import", "import") }
            }
            service.rundeckAuthContextEvaluator=Mock(AuthContextEvaluator){

            }
            ProjectArchiveImportRequest rq = Mock(ProjectArchiveImportRequest) {
                getProject() >> 'importtest'
                getImportConfig() >> true
                getImportACL() >> true
                getImportScm() >> true
            }

        when:
            def result = service.
                importToProject(
                    project, framework, authCtx, getClass().getClassLoader().getResourceAsStream(
                    "test-rdproject.jar"
                ), rq
                )

        then:
            result
            0 * component.doImport(*_)
    }

    def "import project archive with component with matching pattern"() {
        setup:
            ProjectComponent component = Mock(ProjectComponent)
            defineBeans {
                testProjectComponent(InstanceFactoryBean, component, ProjectComponent)
            }
            def project = Mock(IRundeckProject) {
                getName() >> 'importtest'
            }
            def framework = Mock(Framework) {
                getFrameworkProjectsBaseDir() >> { File.createTempDir() }
            }
            def authCtx = Mock(UserAndRolesAuthContext) {
                getUsername() >> { "user" }
                getRoles() >> { ["admin"] as Set }
            }
            service.scheduledExecutionService = Mock(ScheduledExecutionService) {
                loadJobs(_, _, _, _, _, _) >> { [] }
                issueJobChangeEvent(_) >> {}
            }
            service.logFileStorageService = Mock(LogFileStorageService) {
                getFileForExecutionFiletype(_, _, _, _) >> { File.createTempFile("import", "import") }
            }
            service.rundeckAuthContextEvaluator=Mock(AuthContextEvaluator){

            }
            ProjectArchiveImportRequest rq = Mock(ProjectArchiveImportRequest) {
                getProject() >> 'importtest'
                getImportConfig() >> true
                getImportACL() >> true
                getImportScm() >> true
                getImportComponents() >> [webhooks: true]
                getImportOpts() >> [webhooks: [some: 'thing']]
            }

            def tempfile2 = File.createTempFile("test-archive", ".jar")
            tempfile2.deleteOnExit()
            def jarStream = new JarOutputStream(tempfile2.newOutputStream())
            ZipBuilder builder = new ZipBuilder(jarStream)
            builder.dir('test-project/') {
                builder.file('webhooks.yaml') { Writer writer ->
                    writer << 'test-content'
                }
            }
            jarStream.close()
            component.getImportFilePatterns() >> ['webhooks.yaml']
            component.getName() >> 'webhooks'

        when:
            def result = tempfile2.withInputStream { service.importToProject(project, framework, authCtx, it, rq) }

        then:
            result

            1 * component.doImport(_, _, { it.containsKey('webhooks.yaml') }, [some: 'thing']) >> []

        cleanup:
            tempfile2.delete()
    }
    def "import project archive with component unauthorized"() {
        setup:
            ProjectComponent component = Mock(ProjectComponent)
            defineBeans {
                testProjectComponent(InstanceFactoryBean, component, ProjectComponent)
            }
            def project = Mock(IRundeckProject) {
                getName() >> 'importtest'
            }
            def framework = Mock(Framework) {
                getFrameworkProjectsBaseDir() >> { File.createTempDir() }
            }
            def authCtx = Mock(UserAndRolesAuthContext) {
                getUsername() >> { "user" }
                getRoles() >> { ["admin"] as Set }
            }
            service.scheduledExecutionService = Mock(ScheduledExecutionService) {
                loadJobs(_, _, _, _, _, _) >> { [] }
                issueJobChangeEvent(_) >> {}
            }
            service.logFileStorageService = Mock(LogFileStorageService) {
                getFileForExecutionFiletype(_, _, _, _) >> { File.createTempFile("import", "import") }
            }
            component.getImportAuthRequiredActions()>>['admin']
            service.rundeckAuthContextEvaluator=Mock(AuthContextEvaluator){
                1 * authorizeApplicationResourceAny(_, _, ['admin']) >> false
            }
            ProjectArchiveImportRequest rq = Mock(ProjectArchiveImportRequest) {
                getProject() >> 'importtest'
                getImportConfig() >> true
                getImportACL() >> true
                getImportScm() >> true
                getImportComponents() >> [webhooks: true]
                getImportOpts() >> [webhooks: [some: 'thing']]
            }

            def tempfile2 = File.createTempFile("test-archive", ".jar")
            tempfile2.deleteOnExit()
            def jarStream = new JarOutputStream(tempfile2.newOutputStream())
            ZipBuilder builder = new ZipBuilder(jarStream)
            builder.dir('test-project/') {
                builder.file('webhooks.yaml') { Writer writer ->
                    writer << 'test-content'
                }
            }
            jarStream.close()
            component.getImportFilePatterns() >> ['webhooks.yaml']
            component.getName() >> 'webhooks'

        when:
            def result = tempfile2.withInputStream { service.importToProject(project, framework, authCtx, it, rq) }

        then:
            result

            0 * component.doImport(_, _, { it.containsKey('webhooks.yaml') }, [some: 'thing']) >> []

        cleanup:
            tempfile2.delete()
    }

    @Unroll
    def "import project archive with component with matching pattern #pattern"() {
        setup:
            ProjectComponent component = Mock(ProjectComponent)
            defineBeans {
                testProjectComponent(InstanceFactoryBean, component, ProjectComponent)
            }
            def project = Mock(IRundeckProject) {
                getName() >> 'importtest'
            }
            def framework = Mock(Framework) {
                getFrameworkProjectsBaseDir() >> { File.createTempDir() }
            }
            def authCtx = Mock(UserAndRolesAuthContext) {
                getUsername() >> { "user" }
                getRoles() >> { ["admin"] as Set }
            }
            service.scheduledExecutionService = Mock(ScheduledExecutionService) {
                loadJobs(_, _, _, _, _, _) >> { [] }
                issueJobChangeEvent(_) >> {}
            }
            service.logFileStorageService = Mock(LogFileStorageService) {
                getFileForExecutionFiletype(_, _, _, _) >> { File.createTempFile("import", "import") }
            }
            service.rundeckAuthContextEvaluator=Mock(AuthContextEvaluator){

            }
            ProjectArchiveImportRequest rq = Mock(ProjectArchiveImportRequest) {
                getProject() >> 'importtest'
                getImportConfig() >> true
                getImportACL() >> true
                getImportScm() >> true
                getImportComponents() >> [webhooks: true]
                getImportOpts() >> [webhooks: [some: 'thing']]
            }

            def tempfile2 = File.createTempFile("test-archive", ".jar")
            tempfile2.deleteOnExit()
            def jarStream = new JarOutputStream(tempfile2.newOutputStream())
            ZipBuilder builder = new ZipBuilder(jarStream)
            builder.dir('test-project/') {
                builder.dir('something-else') {
                    builder.file('blah.blah') { Writer writer ->
                        writer << 'test-content'
                    }
                }
            }
            jarStream.close()
            component.getImportFilePatterns() >> [pattern]
            component.getName() >> 'webhooks'

        when:
            def result = tempfile2.withInputStream { service.importToProject(project, framework, authCtx, it, rq) }

        then:
            result

            1 * component.doImport(_, _, { it.containsKey('something-else/blah.blah') }, [some: 'thing']) >> []

        cleanup:
            tempfile2.delete()

        where:
            pattern                    | _
            'something-else/blah.blah' | _
            'something-else/.*.blah'    | _
            'something-else/*.*'       | _
            'something-else/*'         | _
            '*/blah.blah'              | _
            '*/.*.blah'              | _
            '*/*.*'                    | _
    }
}
