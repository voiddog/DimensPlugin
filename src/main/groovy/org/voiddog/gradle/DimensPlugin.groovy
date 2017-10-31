package org.voiddog.gradle

import com.android.build.gradle.AppPlugin
import groovy.util.slurpersupport.GPathResult
import groovy.xml.MarkupBuilder
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class DimensPlugin implements Plugin<Project>{

    Project project

    @Override
    void apply(Project project) {
        def isApp = project.plugins.hasPlugin(AppPlugin)
        if (isApp){
            project.extensions.create('dimensConfig', DimensExtension)
            this.project = project
            insertTask()
        }
    }

    void insertTask(){
        if(!project){
            return
        }

        // define task
        def dimensTask = project.task 'generateDimens' doLast {
            def dimensConfig = project.extensions.findByType(DimensExtension)
            if (!dimensConfig){
                return this
            }

            def inputFile = project.file('src/main/res/' + dimensConfig.input + '/dimens.xml')
            if (!inputFile || !inputFile.exists()){
                return this
            }
            def resources = new XmlSlurper().parse(inputFile)
            List<GPathResult> dimensList = []

            // get all dimen element
            resources.children().each { GPathResult dimens ->
                if (dimens.name() == 'dimen'){
                    dimensList.add dimens
                }
            }

            // sort it for binary search
            dimensList.sort { a, b ->
                def nameA = a.'@name'.text()
                def nameB = b.'@name'.text()
                nameA == nameB ? 0 : nameA < nameB ? -1 : 1
            }

            // use to binary search
            def dimensGPathCompare = new Comparator<GPathResult>() {
                @Override
                int compare(GPathResult a, GPathResult b) {
                    def nameA = a.'@name'.text()
                    def nameB = b.'@name'.text()
                    nameA == nameB ? 0 : nameA < nameB ? -1 : 1
                }
            }

            // read other dimens
            dimensConfig.output.each {name, scale ->
                def outFile = project.file('src/main/res/' + name + '/dimens.xml')
                if (outFile.exists()){
                    // verify
                    def subResources = new XmlSlurper().parse(outFile)
                    subResources.children().each {GPathResult dimen ->
                        if (dimen.name() == 'dimen'){
                            def findRes = Collections.binarySearch(dimensList, dimen, dimensGPathCompare)
                            findRes = findRes > 0 ? dimensList[findRes] : null
                            if (!findRes){
                                // not found
                                throw new GradleException("<dimen name='${dimen.'@name'.text()}'> define in subDimens, but not found in dimens")
                            }
                        }
                    }
                } else {
                    if (outFile.getParentFile()){
                        outFile.getParentFile().mkdirs()
                    }
                    outFile.createNewFile()
                }
                if (!outFile.exists()){
                    throw new GradleException("generate dimens file ${name}/dimens.xml failure")
                }

                // write file
                // generate other dimens.xml
                def xml = new MarkupBuilder(outFile.newWriter())

                xml.resources(){
                    dimensList.each {GPathResult dimen ->
                        def value = dimen.text()
                        def m = value =~ /(\d+)dp/
                        if (m){
                            // match
                            def dpValue = m.group(1) as Float
                            value = "${dpValue*scale}dp"
                        } else {
                            m = value =~ /(\d+)sp/
                            if (m){
                                // match
                                def dpValue = m.group(1) as Float
                                value = "${dpValue*scale}sp"
                            }
                        }
                        owner.dimen(name:dimen.'@name', value)
                    }
                }
            }
        }

        project.preBuild.dependsOn dimensTask
    }
}