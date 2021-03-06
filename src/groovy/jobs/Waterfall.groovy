package jobs

import jobs.steps.BuildTableResultStep
import jobs.steps.MultiRowAsGroupDumpTableResultsStep
import jobs.steps.ParametersFileStep
import jobs.steps.RCommandsStep
import jobs.steps.Step
import jobs.steps.helpers.SimpleAddColumnConfigurator
import jobs.steps.helpers.WaterfallColumnConfigurator
import jobs.table.Table
import jobs.table.columns.PrimaryKeyColumn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

import javax.annotation.PostConstruct

@Component
@Scope('job')
class Waterfall extends AbstractAnalysisJob {
    @Autowired
    SimpleAddColumnConfigurator primaryKeyColumnConfigurator

    @Autowired
    WaterfallColumnConfigurator columnConfigurator

    @Autowired
    Table table

    @PostConstruct
    void init() {
        primaryKeyColumnConfigurator.column = new PrimaryKeyColumn(header: 'PATIENT_NUM')
        columnConfigurator.header = 'X'
        columnConfigurator.keyForConceptPath = 'dataNode'
        columnConfigurator.keyForLowValue = 'lowRangeValue'
        columnConfigurator.keyForOperatorForLow = 'lowRangeOperator'
        columnConfigurator.keyForHighValue = 'highRangeValue'
        columnConfigurator.keyForOperatorForHigh = 'highRangeOperator'
    }

    @Override
    protected List<Step> prepareSteps() {

        List<Step> steps = []

        steps << new ParametersFileStep(
                temporaryDirectory: temporaryDirectory,
                params: params)

        steps << new BuildTableResultStep(
                table: table,
                configurators: [primaryKeyColumnConfigurator,
                        columnConfigurator])

        steps << new MultiRowAsGroupDumpTableResultsStep(
                table: table,
                temporaryDirectory: temporaryDirectory)

        steps << new RCommandsStep(
                temporaryDirectory: temporaryDirectory,
                scriptsDirectory: scriptsDirectory,
                rStatements: RStatements,
                studyName: studyName,
                params: params)

        steps
    }

    @Override
    protected List<String> getRStatements() {
        [
                '''source('$pluginDirectory/Waterfall/WaterfallPlotLoader.R')''',
                '''WaterfallPlot.loader(input.filename='outputfile',
                concept='$variablesConceptPaths')'''
        ]
    }

    @Override
    protected getForwardPath() {
        "/waterfall/waterfallOut?jobName=$name"
    }
}
