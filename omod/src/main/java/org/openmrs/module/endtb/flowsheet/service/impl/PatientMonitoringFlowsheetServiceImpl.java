package org.openmrs.module.endtb.flowsheet.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.bahmni.webclients.AllTrustedSSLSocketFactory;
import org.bahmni.webclients.ConnectionDetails;
import org.bahmni.webclients.HttpClient;
import org.bahmni.flowsheet.api.QuestionType;
import org.bahmni.flowsheet.api.Status;
import org.bahmni.flowsheet.api.models.Flowsheet;
import org.bahmni.flowsheet.api.models.Milestone;
import org.bahmni.flowsheet.api.models.Question;
import org.bahmni.flowsheet.api.models.Result;
import org.bahmni.flowsheet.config.Config;
import org.bahmni.flowsheet.config.FlowsheetConfig;
import org.bahmni.flowsheet.config.MilestoneConfig;
import org.bahmni.flowsheet.config.QuestionConfig;
import org.bahmni.flowsheet.definition.HandlerProvider;
import org.bahmni.flowsheet.api.models.QuestionEvaluatorFactory;
import org.bahmni.flowsheet.definition.models.FlowsheetDefinition;
import org.bahmni.flowsheet.definition.models.MilestoneDefinition;
import org.bahmni.flowsheet.definition.models.QuestionDefinition;
import org.bahmni.module.bahmnicore.dao.ObsDao;
import org.bahmni.module.bahmnicore.dao.OrderDao;
import org.bahmni.module.bahmnicore.model.bahmniPatientProgram.BahmniPatientProgram;
import org.bahmni.module.bahmnicore.model.bahmniPatientProgram.PatientProgramAttribute;
import org.bahmni.module.bahmnicore.service.BahmniConceptService;
import org.openmrs.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.bahmniendtb.EndTBConstants;
import org.openmrs.module.bahmniendtb.utils.EndTBProperties;
import org.openmrs.module.endtb.flowsheet.models.FlowsheetAttribute;
import org.bahmni.flowsheet.ui.FlowsheetUI;
import org.openmrs.module.endtb.flowsheet.service.PatientMonitoringFlowsheetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.util.*;

@Service
public class PatientMonitoringFlowsheetServiceImpl implements PatientMonitoringFlowsheetService {

    private OrderDao orderDao;
    private ObsDao obsDao;
    private BahmniConceptService bahmniConceptService;
    private HandlerProvider handlerProvider;
    private QuestionEvaluatorFactory questionEvaluatorFactory;
    private HttpClient httpClient;
    private EndTBProperties endTBProperties;

    @Autowired
    public PatientMonitoringFlowsheetServiceImpl(OrderDao orderDao, ObsDao obsDao,
            BahmniConceptService bahmniConceptService, HandlerProvider handlerProvider,
            QuestionEvaluatorFactory factory, EndTBProperties endTBProperties) {
        this.orderDao = orderDao;
        this.obsDao = obsDao;
        this.bahmniConceptService = bahmniConceptService;
        this.handlerProvider = handlerProvider;
        this.questionEvaluatorFactory = factory;
        this.endTBProperties = endTBProperties;
    }

    @Override
    public FlowsheetUI getFlowsheetForPatientProgram(PatientProgram patientProgram, Date startDate, Date endDate,
            String configFilePath) throws Exception {
        FlowsheetUI presentationFlowsheet = new FlowsheetUI();
        if (startDate == null) {
            return presentationFlowsheet;
        }
        FlowsheetConfig flowsheetConfig = getFlowsheetConfig(configFilePath);
        FlowsheetDefinition flowsheetDefinition = getFlowsheetDefinitionFromConfig(flowsheetConfig, startDate);
        Flowsheet flowsheet = flowsheetDefinition.createFlowsheet(patientProgram);

        questionEvaluatorFactory.init(patientProgram, flowsheet.getObsFlowsheetConcepts());
        flowsheet.evaluate(questionEvaluatorFactory);

        Set<Milestone> milestones = flowsheet.getMilestones();

        Set<String> floatingMilestoneNames = getFloatingMilestoneNames(flowsheetConfig.getMilestoneConfigs());
        setNotApplicableStatusToFixedMilestones(endDate, milestones, floatingMilestoneNames);

        String highlightedCurrentMilestoneName = findCurrentMonthMilestone(milestones, floatingMilestoneNames);
        String treatmentEndMilestoneName = findEndDateMilestone(milestones, endDate, floatingMilestoneNames);

        List<QuestionConfig> questionConfigs = flowsheetConfig.getQuestionConfigs();

        Map<String, List<String>> flowsheetData = new LinkedHashMap<>();
        for (QuestionConfig questionConfig : questionConfigs) {
            String questionName = questionConfig.getName();
            List<String> colorCodes = new LinkedList<>();
            for (Milestone milestone : milestones) {
                Question milestoneQuestion = getQuestionFromSet(milestone.getQuestions(), questionName);
                colorCodes.add(getColorCodeForStatus(milestoneQuestion, flowsheetConfig));
            }
            flowsheetData.put(questionName, colorCodes);
        }

        List<Milestone> flowsheetMilestones = new ArrayList<>();
        for (Milestone milestone : milestones) {
            milestone.setQuestions(new LinkedHashSet<Question>());
            flowsheetMilestones.add(milestone);
        }

        presentationFlowsheet.setMilestones(flowsheetMilestones);
        presentationFlowsheet.setHighlightedCurrentMilestone(highlightedCurrentMilestoneName);
        presentationFlowsheet.setEndDateMilestone(treatmentEndMilestoneName);
        presentationFlowsheet.setFlowsheetData(flowsheetData);
        presentationFlowsheet.setFlowsheetConfig(flowsheetConfig);

        return presentationFlowsheet;
    }

    @Override
    public FlowsheetDefinition getFlowsheetDefinitionFromConfig(FlowsheetConfig flowsheetConfig, Date startDate) {

        Set<MilestoneDefinition> milestoneDefinitions = new LinkedHashSet<>();
        List<MilestoneConfig> milestoneConfigs = flowsheetConfig.getMilestoneConfigs();
        for (MilestoneConfig milestoneConfig : milestoneConfigs) {
            Config config = milestoneConfig.getConfig();
            Map<String, String> configMap = new LinkedHashMap<>();
            configMap.put("min", config.getMin());
            configMap.put("max", config.getMax());
            MilestoneDefinition milestoneDefinition = new MilestoneDefinition(milestoneConfig.getName(), configMap,
                    milestoneConfig.getHandler(), handlerProvider);

            Set<QuestionDefinition> questionDefinitions = new LinkedHashSet<>();
            for (String questionName : milestoneConfig.getQuestionNames()) {
                QuestionConfig questionConfig = flowsheetConfig.getQuestionConfigByName(questionName);
                if (questionConfig != null) {
                    Set<Concept> conceptSet = new LinkedHashSet<>();
                    for (String conceptName : questionConfig.getConcepts()) {
                        conceptSet.add(bahmniConceptService.getConceptByFullySpecifiedName(conceptName));
                    }
                    questionDefinitions.add(new QuestionDefinition(questionConfig.getName(), conceptSet,
                            getQuestionType(questionConfig.getType())));
                }
            }
            milestoneDefinition.setQuestionDefinitions(questionDefinitions);
            milestoneDefinitions.add(milestoneDefinition);
        }
        return new FlowsheetDefinition(startDate, milestoneDefinitions);
    }

    @Override
    public FlowsheetAttribute getFlowsheetAttributesForPatientProgram(BahmniPatientProgram bahmniPatientProgram,
            PatientIdentifierType primaryIdentifierType, OrderType orderType, Set<Concept> concepts) {
        FlowsheetAttribute flowsheetAttribute = new FlowsheetAttribute();
        List<Obs> startDateConceptObs = obsDao.getObsByPatientProgramUuidAndConceptNames(bahmniPatientProgram.getUuid(),
                Arrays.asList(EndTBConstants.TI_TREATMENT_START_DATE), null, null, null, null);
        Date startDate = null;
        if (CollectionUtils.isNotEmpty(startDateConceptObs)) {
            startDate = startDateConceptObs.get(0).getValueDate();
        }
        Date newDrugTreatmentStartDate = getNewDrugTreatmentStartDate(bahmniPatientProgram.getUuid(), orderType,
                concepts);
        List<Obs> consentForEndTbStudyObs = obsDao.getObsByPatientProgramUuidAndConceptNames(
                bahmniPatientProgram.getUuid(), Arrays.asList(EndTBConstants.FSN_TI_ENDTB_STUDY_CONSENT_QUESTION), null,
                null, null, null);
        String consentForEndTbStudy = null;

        if (CollectionUtils.isNotEmpty(consentForEndTbStudyObs)) {
            consentForEndTbStudy = consentForEndTbStudyObs.get(0).getValueCoded()
                    .getShortNameInLocale(Context.getUserContext().getLocale()).getName();
        }

        List<Obs> hivSeroStatusObs = obsDao.getObsByPatientProgramUuidAndConceptNames(bahmniPatientProgram.getUuid(),
                Arrays.asList(EndTBConstants.BASLINE_HIV_SEROSTATUS_RESULT, EndTBConstants.LAB_HIV_TEST_RESULT), null,
                null, null, null);
        String hivStatus = null;

        if (CollectionUtils.isNotEmpty(hivSeroStatusObs)) {
            hivStatus = hivSeroStatusObs.get(0).getValueCoded().getName().getName();
        }

        String baselineXRayStatus = null;

        if (startDate != null) {
            Date minDate = DateUtils.addDays(startDate, -90);
            Date maxDate = DateUtils.addDays(startDate, 30);
            List<Obs> baslineXrayObs = obsDao.getObsByPatientProgramUuidAndConceptNames(bahmniPatientProgram.getUuid(),
                    Arrays.asList(EndTBConstants.XRAY_EXTENT_OF_DISEASE), null, null, null, null);
            baselineXRayStatus = isObsDatePresentWithinDateRange(minDate, maxDate, baslineXrayObs);
        }

        flowsheetAttribute.setNewDrugTreatmentStartDate(newDrugTreatmentStartDate);
        flowsheetAttribute.setMdrtbTreatmentStartDate(startDate);
        flowsheetAttribute.setTreatmentRegistrationNumber(
                getProgramAttribute(bahmniPatientProgram, EndTBConstants.PROGRAM_ATTRIBUTE_REG_NO));
        flowsheetAttribute.setPatientEMRID(
                bahmniPatientProgram.getPatient().getPatientIdentifier(primaryIdentifierType).getIdentifier());
        flowsheetAttribute.setConsentForEndtbStudy(consentForEndTbStudy);
        flowsheetAttribute.setHivStatus(hivStatus);
        flowsheetAttribute.setBaselineXRayStatus(baselineXRayStatus);
        return flowsheetAttribute;
    }

    private String isObsDatePresentWithinDateRange(Date minDate, Date maxDate, List<Obs> baslineXrayObs) {
        for (Obs obs : baslineXrayObs) {
            Date obsDate = obs.getObsDatetime();
            if (obsDate.equals(minDate) || obsDate.equals(maxDate)) {
                return "Yes";
            }
            if (obsDate.after(minDate) && obsDate.before(maxDate)) {
                return "Yes";
            }
        }
        return "No";
    }

    @Override
    public Date getStartDateForDrugConcepts(String patientProgramUuid, Set<String> drugConcepts, OrderType orderType) {
        return getNewDrugTreatmentStartDate(patientProgramUuid, orderType, getConceptObjects(drugConcepts));
    }

    private QuestionType getQuestionType(String type) {
        if (type.equalsIgnoreCase("Drug")) {
            return QuestionType.DRUG;
        }
        return QuestionType.OBS;
    }

    private Question getQuestionFromSet(Set<Question> questions, String name) {
        for (Question question : questions) {
            if (question.getName().equals(name))
                return question;
        }
        return null;
    }

    private Set<Concept> getConceptObjects(Set<String> conceptNames) {
        Set<Concept> conceptsList = new HashSet<>();
        for (String concept : conceptNames) {
            conceptsList.add(bahmniConceptService.getConceptByFullySpecifiedName(concept));
        }
        return conceptsList;
    }

    private Date getNewDrugTreatmentStartDate(String patientProgramUuid, OrderType orderType, Set<Concept> concepts) {
        List<Order> orders = orderDao.getOrdersByPatientProgram(patientProgramUuid, orderType, concepts);
        if (orders.size() > 0) {
            Order firstOrder = orders.get(0);
            Date newDrugTreatmentStartDate = firstOrder.getScheduledDate() != null ? firstOrder.getScheduledDate()
                    : firstOrder.getDateActivated();
            for (Order order : orders) {
                Date toCompare = order.getScheduledDate() != null ? order.getScheduledDate() : order.getDateActivated();
                if (newDrugTreatmentStartDate.compareTo(toCompare) > 0) {
                    newDrugTreatmentStartDate = toCompare;
                }
            }
            return newDrugTreatmentStartDate;
        }
        return null;
    }

    private String getProgramAttribute(BahmniPatientProgram bahmniPatientProgram, String attribute) {
        for (PatientProgramAttribute patientProgramAttribute : bahmniPatientProgram.getActiveAttributes()) {
            if (patientProgramAttribute.getAttributeType().getName().equals(attribute))
                return patientProgramAttribute.getValueReference();
        }
        return "";
    }

    private String findMilestoneContainingDateInFixedMilestones(Set<Milestone> milestones, Date dateToCheck,
            Set<String> floatingMilestones) {
        if (dateToCheck != null) {
            for (Milestone milestone : milestones) {

                boolean notFloating = (!floatingMilestones.contains(milestone.getName()));
                boolean onOrAfterStartOfMilestone = (milestone.getStartDate().before(dateToCheck)
                        || DateUtils.isSameDay(milestone.getStartDate(), dateToCheck));
                boolean onOrBeforeEndOfMilestone = (milestone.getEndDate().after(dateToCheck)
                        || DateUtils.isSameDay(milestone.getEndDate(), dateToCheck));

                if (notFloating && onOrAfterStartOfMilestone && onOrBeforeEndOfMilestone) {
                    return milestone.getName();
                }
            }
        }
        return "";
    }

    private String findCurrentMonthMilestone(Set<Milestone> milestones, Set<String> floatingMilestones) {
        return findMilestoneContainingDateInFixedMilestones(milestones, new Date(), floatingMilestones);
    }

    private String findEndDateMilestone(Set<Milestone> milestones, Date endDate, Set<String> floatingMilestones) {
        return findMilestoneContainingDateInFixedMilestones(milestones, endDate, floatingMilestones);
    }

    private Set<String> getFloatingMilestoneNames(List<MilestoneConfig> milestoneConfigs) {
        Set<String> floatingMilestoneNames = new HashSet<>();
        for (MilestoneConfig milestoneConfig : milestoneConfigs) {
            if (milestoneConfig.getHandler() != null) {
                floatingMilestoneNames.add(milestoneConfig.getName());
            }
        }
        return floatingMilestoneNames;
    }

    private void setNotApplicableStatusToFixedMilestones(Date endDate, Set<Milestone> milestones,
            Set<String> floatingMilestones) {
        for (Milestone milestone : milestones) {
            if (!floatingMilestones.contains(milestone.getName()))
                for (Question question : milestone.getQuestions()) {
                    if (endDate != null && milestone.getStartDate().after(endDate)
                            && !question.getResult().getStatus().equals(Status.DATA_ADDED)) {
                        question.setResult(new Result(Status.NOT_APPLICABLE));
                    }
                }
        }
    }

    public Registry<ConnectionSocketFactory> schemeRegistry(SSLConnectionSocketFactory allTrustSSLSocketFactory) {

        Registry<ConnectionSocketFactory> schemeRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("https", allTrustSSLSocketFactory)
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .build();
        return schemeRegistry;
    }

    public SSLConnectionSocketFactory allTrustSSLSocketFactory() {
        return new AllTrustedSSLSocketFactory().getSSLSocketFactory();
    }

    private FlowsheetConfig getFlowsheetConfig(String configFilePath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        if (new File(configFilePath).exists()) {
            FlowsheetConfig flowsheetConfig = mapper.readValue(new File(configFilePath), FlowsheetConfig.class);
            return flowsheetConfig;
        } else {
            URI uri = new URI(configFilePath);
            PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                    schemeRegistry(allTrustSSLSocketFactory()));
            connectionManager.setDefaultMaxPerRoute(10);
            ConnectionDetails connectionDetails = new ConnectionDetails(
                    endTBProperties.getAuthUrl(),
                    endTBProperties.getOpenmrsUser(),
                    endTBProperties.getOpenmrsPassword(),
                    Integer.parseInt(endTBProperties.getConnectionTimeoutInMilliseconds()),
                    Integer.parseInt(endTBProperties.getReplyTimeoutInMilliseconds()), connectionManager);

            httpClient = new HttpClient(connectionDetails);
            String data = httpClient.get(uri);
            FlowsheetConfig flowsheetConfig = mapper.readValue(data, FlowsheetConfig.class);
            return flowsheetConfig;
        }
    }

    private String getColorCodeForStatus(Question milestoneQuestion, FlowsheetConfig flowsheetConf) {

        String result = "grey";

        if (milestoneQuestion == null || milestoneQuestion.getResult() == null) {
            return result;
        }

        Status status = milestoneQuestion.getResult().getStatus();

        if (status.equals(Status.DATA_ADDED)) {
            result = "green";
        }

        if (status.equals(Status.PLANNED)) {
            if (flowsheetConf.getTrackPlanned()) {
                result = "yellow";
            }
        }

        if (status.equals(Status.PENDING)) {
            if (flowsheetConf.getTrackPending()) {
                result = "purple";
            }
        }

        if (status.equals(Status.NOT_APPLICABLE)) {
            result = "grey";
        }

        return result;
    }

}
