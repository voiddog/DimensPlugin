package org.voiddog.gradle

import com.android.ddmlib.Log
import groovy.util.slurpersupport.GPathResult
import groovy.xml.MarkupBuilder
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

import java.text.DecimalFormat

class DimensPlugin implements Plugin<Project>{

    Project project

    @Override
    void apply(Project project) {
        project.extensions.create('dimensConfig', DimensExtension)
        this.project = project
        insertTask()
    }

    void insertTask(){
        if(!project){
            return
        }

        // define task
        def dimensTask = project.task 'generateDimens' doLast {
            def dimensConfig = project.extensions.findByType(DimensExtension)
            if (!dimensConfig || !dimensConfig.input){
                return this
            }
            if (!dimensConfig.input.endsWith('.xml')){
                throw new GradleException("${dimensConfig.input} not a xml file")
            }
            dimensConfig.output && dimensConfig.output.each {key,value->
                if (!key || !key.endsWith('.xml')){
                    throw new GradleException("${key} not a xml file")
                }
            }

            def inputFile = project.file('src/main/res/' + dimensConfig.input)
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
                def outFile = project.file('src/main/res/' + name)
                if (outFile.exists()){
                    // verify
                    def subResources = new XmlSlurper().parse(outFile)
                    subResources.children().each {GPathResult dimen ->
                        if (dimen.name() == 'dimen'){
                            def findRes = Collections.binarySearch(dimensList, dimen, dimensGPathCompare)
                            findRes = findRes >= 0 ? dimensList[findRes] : null
                            if (!findRes){
                                // not found
                                throw new GradleException("<dimen name='${dimen.'@name'.text()}'> define in ${name}, but not found in ${dimensConfig.input}")
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
                        def m = value =~ /(\d+\.?\d*)dp/
                        if (m){
                            // match
                            def dpValue = m.group(1) as Float
                            if (value.startsWith('-')){
                                dpValue *= -1
                            }
                            DecimalFormat df = new DecimalFormat("#.#")
                            value = "${df.format(dpValue*scale)}dp"
                        } else {
                            m = value =~ /(\d+\.?\d*)sp/
                            if (m){
                                // match
                                def dpValue = m.group(1) as Float
                                DecimalFormat df = new DecimalFormat("#.#")
                                value = "${df.format(dpValue*scale)}sp"
                            }
                        }
                        owner.dimen(name:dimen.'@name', value)
                    }
                }
            }
        }


        project.afterEvaluate {
            if (project.hasProperty('preBuild')){
                project.preBuild.dependsOn dimensTask
            } else {
                Log.e('Dimens', "task preBuild not found in: ${project.name}")
            }
        }
    }
}