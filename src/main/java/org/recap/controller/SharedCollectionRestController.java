package org.recap.controller;

import org.apache.camel.Exchange;
import org.apache.commons.collections.CollectionUtils;
import org.recap.RecapConstants;
import org.recap.RecapCommonConstants;
import org.recap.model.accession.AccessionRequest;
import org.recap.model.accession.AccessionResponse;
import org.recap.model.accession.AccessionSummary;
import org.recap.model.jpa.AccessionEntity;
import org.recap.model.submitcollection.SubmitCollectionResponse;
import org.recap.service.accession.AccessionService;
import org.recap.service.accession.BulkAccessionService;
import org.recap.service.common.SetupDataService;
import org.recap.service.submitcollection.SubmitCollectionBatchService;
import org.recap.service.submitcollection.SubmitCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by premkb on 21/12/16.
 */
@RestController
@RequestMapping("/sharedCollection")
public class SharedCollectionRestController {

    private static final Logger logger = LoggerFactory.getLogger(SharedCollectionRestController.class);

    @Autowired
    private SubmitCollectionService submitCollectionService;

    @Autowired
    private SubmitCollectionBatchService submitCollectionBatchService;

    @Autowired
    private SetupDataService setupDataService;

    @Autowired
    AccessionService accessionService;

    @Autowired
    BulkAccessionService bulkAccessionService;

    @Value("${ongoing.accession.input.limit}")
    private Integer inputLimit;



    /**
     * This method is used to save the accession and send the response.
     *
     * @param accessionRequestList the accession request list
     * @return the response entity
     */
    @PostMapping(value = "/accessionBatch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity accessionBatch(@RequestBody List<AccessionRequest> accessionRequestList) {
        String responseMessage = bulkAccessionService.saveRequest(accessionRequestList);
        return new ResponseEntity(responseMessage, getHttpHeaders(), HttpStatus.OK);
    }

    /**
     * This method is used to perform accession for the given list of accessionRequests.
     *
     * @param accessionRequestList the accession request list
     * @return the response entity
     */
    @PostMapping(value = "/accession", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity accession(@RequestBody List<AccessionRequest> accessionRequestList, Exchange exchange) {
        ResponseEntity responseEntity;
        List<AccessionResponse> accessionResponsesList;
        if (accessionRequestList.size() > inputLimit) {
            accessionResponsesList = getAccessionResponses();
            return new ResponseEntity(accessionResponsesList, getHttpHeaders(), HttpStatus.OK);
        } else {
            String accessionType = RecapConstants.ACCESSION_SUMMARY;
            AccessionSummary accessionSummary = new AccessionSummary(accessionType);
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            logger.info("Total record for Accession : {}" , accessionRequestList.size());
            accessionResponsesList = accessionService.doAccession(accessionRequestList, accessionSummary,exchange);
            stopWatch.stop();
            accessionSummary.setTimeElapsed(stopWatch.getTotalTimeSeconds() + " Secs");
            logger.info(accessionSummary.toString());
            accessionService.createSummaryReport(accessionSummary.toString(), accessionType);
            responseEntity = new ResponseEntity(accessionResponsesList, getHttpHeaders(), HttpStatus.OK);
        }
        return responseEntity;
    }

    /**
     * This method performs ongoing accession job.
     *
     * @return the string
     */
    @GetMapping(value = "/ongoingAccessionJob")
    @ResponseBody
    public String ongoingAccessionJob(Exchange exchange) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        String status;
        List<AccessionEntity> accessionEntities = bulkAccessionService.getAccessionEntities(RecapConstants.PENDING);
        List<AccessionRequest> accessionRequestList = bulkAccessionService.getAccessionRequest(accessionEntities);
        String accessionType = RecapConstants.BULK_ACCESSION_SUMMARY;
        AccessionSummary accessionSummary = new AccessionSummary(accessionType);

        if (CollectionUtils.isNotEmpty(accessionRequestList)) {
            logger.info("Total record for Bulk Accession : {}", accessionRequestList.size());
            bulkAccessionService.updateStatusForAccessionEntities(accessionEntities, RecapConstants.PROCESSING);
            bulkAccessionService.doAccession(accessionRequestList, accessionSummary,exchange);
            if (accessionSummary.getSuccessRecords() != 0) {
                status = RecapCommonConstants.SUCCESS;
            } else {
                status = RecapCommonConstants.FAILURE;
            }
        } else {
            status = RecapCommonConstants.ACCESSION_NO_PENDING_REQUESTS;
        }
        bulkAccessionService.updateStatusForAccessionEntities(accessionEntities, RecapCommonConstants.COMPLETE_STATUS);
        stopWatch.stop();
        accessionSummary.setTimeElapsed(stopWatch.getTotalTimeSeconds() + " Secs");

        bulkAccessionService.createSummaryReport(accessionSummary.toString(), accessionType);
        logger.info("Total time taken for processing {} records : {} secs", accessionRequestList.size(), stopWatch.getTotalTimeSeconds());
        logger.info(accessionSummary.toString());

        return status;
    }

    private List<AccessionResponse> getAccessionResponses() {
        List<AccessionResponse> accessionResponsesList;
        accessionResponsesList = new ArrayList<>();
        AccessionResponse accessionResponse = new AccessionResponse();
        accessionResponse.setItemBarcode("");
        accessionResponsesList.add(accessionResponse);
        accessionResponse.setMessage(RecapConstants.ONGOING_ACCESSION_LIMIT_EXCEED_MESSAGE + inputLimit);
        return accessionResponsesList;
    }

    /**
     * This controller method is the entry point for submit collection which receives
     * input xml either in marc xml or scsb xml and pass it to the service class
     *
     * @param requestParameters holds map of input xml string, institution, cdg protetion flag
     * @return the response entity
     */
    @PostMapping(value = "/submitCollection")
    @ResponseBody
    public ResponseEntity submitCollection(@RequestParam Map<String,Object> requestParameters){
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ResponseEntity responseEntity;
        String inputRecords = (String) requestParameters.get(RecapCommonConstants.INPUT_RECORDS);
        String institution = (String) requestParameters.get(RecapCommonConstants.INSTITUTION);
        Integer institutionId = (Integer) setupDataService.getInstitutionCodeIdMap().get(institution);
        Boolean isCGDProtection = Boolean.valueOf((String) requestParameters.get(RecapCommonConstants.IS_CGD_PROTECTED));

        List<Integer> reportRecordNumberList = new ArrayList<>();
        Set<Integer> processedBibIdSet = new HashSet<>();
        List<Map<String,String>> idMapToRemoveIndexList = new ArrayList<>();//Added to remove dummy record in solr
        List<Map<String,String>> bibIdMapToRemoveIndexList = new ArrayList<>();//Added to remove orphan record while unlinking
        Set<String> updatedBoundWithDummyRecordOwnInstBibIdSet = new HashSet<>();
        List<SubmitCollectionResponse> submitCollectionResponseList;
        try {
            submitCollectionResponseList = submitCollectionBatchService.process(institution,inputRecords,processedBibIdSet,idMapToRemoveIndexList,bibIdMapToRemoveIndexList,"",reportRecordNumberList, true,isCGDProtection,updatedBoundWithDummyRecordOwnInstBibIdSet);
            if (!processedBibIdSet.isEmpty()) {
                logger.info("Calling indexing service to update data");
                submitCollectionService.indexData(processedBibIdSet);
            }
            if(!updatedBoundWithDummyRecordOwnInstBibIdSet.isEmpty()){
                logger.info("Updated boudwith dummy record own inst bib id size-->{}",updatedBoundWithDummyRecordOwnInstBibIdSet.size());
                submitCollectionService.indexDataUsingOwningInstBibId(new ArrayList<>(updatedBoundWithDummyRecordOwnInstBibIdSet),institutionId);
            }
            if (!idMapToRemoveIndexList.isEmpty() || !bibIdMapToRemoveIndexList.isEmpty()) {//remove the incomplete record from solr index
                logger.info("Calling indexing to remove dummy records");
                new Thread(() -> {
                    submitCollectionService.removeBibFromSolrIndex(bibIdMapToRemoveIndexList);
                    submitCollectionService.removeSolrIndex(idMapToRemoveIndexList);
                    logger.info("Removed dummy records from solr");
                }).start();
            }
            submitCollectionBatchService.generateSubmitCollectionReportFile(reportRecordNumberList);
            responseEntity = new ResponseEntity(submitCollectionResponseList,getHttpHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            logger.error(RecapCommonConstants.LOG_ERROR,e);
            responseEntity = new ResponseEntity(RecapConstants.SUBMIT_COLLECTION_INTERNAL_ERROR,getHttpHeaders(), HttpStatus.OK);
        }
        stopWatch.stop();
        logger.info("Total time taken to process submit collection through rest api--->{} sec",stopWatch.getTotalTimeSeconds());
        return responseEntity;
    }

    private HttpHeaders getHttpHeaders() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add(RecapCommonConstants.RESPONSE_DATE, new Date().toString());
        return responseHeaders;
    }
}