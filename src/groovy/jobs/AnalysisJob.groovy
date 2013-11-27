package jobs

import au.com.bytecode.opencsv.CSVWriter
import com.recomdata.transmart.util.RUtil
import grails.util.Holders
import groovy.text.SimpleTemplateEngine
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.rosuda.REngine.REXP
import org.rosuda.REngine.Rserve.RConnection
import org.rosuda.REngine.Rserve.RserveException
import org.transmartproject.core.dataquery.TabularResult

abstract class AnalysisJob implements Job {
    Map jobDataMap
    String name
    File temporaryDirectory

    abstract protected void writeData(TabularResult results)

    abstract protected void runAnalysis()

    abstract protected TabularResult fetchResults()

    abstract protected void renderOutput()

    /**
     * This method is called by Quartz (never directly) and is the main method of the extending classes
     *
     * @param context
     * @throws JobExecutionException
     */
    @Override
    void execute(JobExecutionContext context) {
        if (isFoulJobName(context)) {
            throw new JobExecutionException("Job name mangled")
        }
        name = context.jobDetail.jobDataMap["jobName"]
        jobDataMap = context.jobDetail.jobDataMap

        try {
            setupTemporaryDirectory()

            writeParametersFile()
            TabularResult results = fetchResults()
            writeData(results)
            runAnalysis()
            renderOutput()
        } catch (Exception e) {
            log.error("Some exception occurred in the processing pipe", e)
            updateStatus("Error")
        }
    }

    private static boolean isFoulJobName(JobExecutionContext context) {
        if (context.jobDetail.jobDataMap["jobName"] ==~ /^[0-9A-Za-z-]+$/) {
            return false
        }
        return true
    }

    protected void setupTemporaryDirectory() {
        //FIXME: This is stupid of course, taking the 'name' from the client. What if the name is '../../'?
        temporaryDirectory = new File(new File(Holders.config.RModules.tempFolderDirectory, name), 'workingDirectory')
        temporaryDirectory.mkdirs()
    }

    /**
     * The file being written in this method is potentially used in the R scripts
     */
    protected void writeParametersFile() {
        File jobInfoFile = new File(temporaryDirectory, 'jobInfo.txt')

        jobInfoFile.withWriter { BufferedWriter it ->
            it.writeLine 'Parameters'
            jobDataMap.each { key, value ->
                it.writeLine "\t$key -> $value"
            }
        }
    }

    protected void withDefaultCsvWriter(TabularResult results, Closure constructFile) {
        try {
            File output = new File(temporaryDirectory, 'outputfile')
            output.createNewFile()
            output.withWriter { writer ->
                CSVWriter csvWriter = new CSVWriter(writer, '\t' as char)
                constructFile.call(csvWriter)
            }
        } finally {
          results.close()
        }
    }

    /**
     *
     * @param stepList  A list of R commands as Strings
     */
    protected void runRCommandList(List<String> stepList) {
        String study = i2b2ExportHelperService.findStudyAccessions([jobDataMap.result_instance_id1])

        //Establish a connection to R Server.
        RConnection rConnection = new RConnection();

        //Run the R command to set the working directory to our temp directory.
        rConnection.eval("setwd('$temporaryDirectory')");

        //For each R step there is a list of commands.
        stepList.each { String currentCommand ->

            /**
             * Please make sure that any and all variables you add to the map here are placed _after_ the putAll
             * as otherwise you create a potential security vulnerability
             */
            Map vars = [:]
            vars.putAll jobDataMap
            escapeUserStrings(vars)

            vars.pluginDirectory = Holders.config.RModules.pluginScriptDirectory
            vars.temporaryDirectory = new File(temporaryDirectory, "subset1_" + study).absolutePath

            String finalCommand = processTemplates(currentCommand, vars)
            log.info "About to trigger R command:$finalCommand"
            // REXP rObject = rConnection.parseAndEval("try($finalCommand, silent=TRUE)")
            REXP rObject = rConnection.parseAndEval("try($finalCommand, silent=FALSE)")

            if (rObject.inherits("try-error")) {
                log.error "R command failure for:$finalCommand"
                handleError(rObject, rConnection)
            }
        }
    }

    private static void escapeUserStrings(Map vars) {
        vars.each { k, v ->
            if (v.getClass() == String) {
                vars[k] = RUtil.escapeRStringContent(v)
            }
        }
    }

    private void handleError(REXP rObject, RConnection rConnection) throws RserveException {
        //Grab the error R gave us.
        String rError = rObject.asString()

        //This is the error we will eventually throw.
        RserveException newError = null

        //If it is a friendly error, use that, otherwise throw the default message.
        if (rError ==~ /.*\|\|FRIENDLY\|\|.*/) {
            rError = rError.replaceFirst(/.*\|\|FRIENDLY\|\|/, "")
            newError = new RserveException(rConnection, rError)
        } else {
            newError = new RserveException(rConnection, "There was an error running the R script for your job. Please contact an administrator.")
        }

        throw newError
    }

    /**
     * This method takes a conceptPath provided by the frontend and turns it into a String representation of
     * a concept key which the AssayConstraint can use. Such a string is pulled apart later in a
     * table_access.c_table_cd part and a concept_dimension.concept_path part.
     * The operation duplicates the first element of the conceptPath and prefixes it to the original with a double
     * backslash.
     * @param conceptPath
     * @return String conceptKey
     */
    protected static String createConceptKeyFrom(String conceptPath) {
        // This crazy dance with slashes is "expected behaviour"
        // as per http://groovy.codehaus.org/Strings+and+GString (search for Slashy Strings)
        def bs = '\\\\'
        "\\\\" + (conceptPath =~ /$bs([\w ]+)$bs/)[0][-1] + conceptPath
    }

    protected static String processTemplates(String template, Map vars) {
        SimpleTemplateEngine engine = new SimpleTemplateEngine()
        engine.createTemplate(template).make(vars)
    }

    protected void updateStatus(String status, String viewerUrl = null) {
        log.info "updateStatus called for status:$status, viewerUrl:$viewerUrl"
        asyncJobService.updateStatus(name, status, viewerUrl)
    }

    protected def getI2b2ExportHelperService() {
        jobDataMap.grailsApplication.mainContext.i2b2ExportHelperService
    }

    protected def getAsyncJobService() {
        jobDataMap.grailsApplication.mainContext.asyncJobService
    }
}