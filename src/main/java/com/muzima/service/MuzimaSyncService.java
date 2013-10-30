package com.muzima.service;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import com.muzima.MuzimaApplication;
import com.muzima.api.context.Context;
import com.muzima.api.model.*;
import com.muzima.controller.*;
import com.muzima.utils.Constants;
import org.apache.lucene.queryParser.ParseException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.muzima.utils.Constants.COHORT_PREFIX_PREF;
import static com.muzima.utils.Constants.COHORT_PREFIX_PREF_KEY;
import static com.muzima.utils.Constants.DataSyncServiceConstants.SyncStatusConstants.*;

public class MuzimaSyncService {
    private static final String TAG = "MuzimaSyncService";

    private MuzimaApplication muzimaApplication;
    private FormController formController;
    private ConceptController conceptController;
    private CohortController cohortController;
    private PatientController patientController;

    public MuzimaSyncService(MuzimaApplication muzimaContext) {
        this.muzimaApplication = muzimaContext;
        formController = muzimaApplication.getFormController();
        conceptController = muzimaApplication.getConceptController();
        cohortController = muzimaApplication.getCohortController();
        patientController = muzimaApplication.getPatientController();
    }

    public int authenticate(String[] credentials) {
        String username = credentials[0];
        String password = credentials[1];
        String server = credentials[2];

        Context muzimaContext = muzimaApplication.getMuzimaContext();
        try {
            muzimaContext.openSession();
            if (!muzimaContext.isAuthenticated()) {
                muzimaContext.authenticate(username, password, server);
            }
        } catch (ConnectException e) {
            Log.e(TAG, "ConnectException Exception thrown while authentication " + e.getMessage());
            return CONNECTION_ERROR;
        } catch (ParseException e) {
            Log.e(TAG, "ParseException Exception thrown while authentication " + e.getMessage());
            return PARSING_ERROR;
        } catch (IOException e) {
            Log.e(TAG, "IOException Exception thrown while authentication " + e.getMessage());
            return AUTHENTICATION_ERROR;
        } finally {
            if (muzimaContext != null)
                muzimaContext.closeSession();
        }

        return AUTHENTICATION_SUCCESS;
    }

    public int[] downloadForms() {
        int[] result = new int[2];

        try {
            List<Form> forms;
            forms = formController.downloadAllForms();
            Log.i(TAG, "Form download successful");
            formController.deleteAllForms();
            Log.i(TAG, "Old forms are deleted");
            formController.saveAllForms(forms);
            Log.i(TAG, "New forms are saved");

            result[0] = Constants.DataSyncServiceConstants.SyncStatusConstants.SUCCESS;
            result[1] = forms.size();

        } catch (FormController.FormFetchException e) {
            Log.e(TAG, "Exception when trying to download forms", e);
            result[0] = DOWNLOAD_ERROR;
            return result;
        } catch (FormController.FormSaveException e) {
            Log.e(TAG, "Exception when trying to save forms", e);
            result[0] = SAVE_ERROR;
            return result;
        } catch (FormController.FormDeleteException e) {
            Log.e(TAG, "Exception occurred while deleting existing forms", e);
            result[0] = DELETE_ERROR;
            return result;
        }
        return result;
    }

    public int[] downloadFormTemplates(String[] formIds) {
        int[] result = new int[3];

        try {
            List<FormTemplate> formTemplates = formController.downloadFormTemplates(formIds);
            Log.i(TAG, formTemplates.size() + " form template download successful");

            formController.replaceFormTemplates(formTemplates);
            List<Concept> concepts = getRelatedConcepts(formTemplates);
            conceptController.saveConcepts(concepts);

            new PreferenceHelper(muzimaApplication).addConcepts(concepts);
            Log.i(TAG, "Form templates replaced");

            result[0] = SUCCESS;
            result[1] = formTemplates.size();
            result[2] = concepts.size();
        } catch (FormController.FormSaveException e) {
            Log.e(TAG, "Exception when trying to save forms", e);
            result[0] = SAVE_ERROR;
            return result;
        } catch (FormController.FormFetchException e) {
            Log.e(TAG, "Exception when trying to download forms", e);
            result[0] = DOWNLOAD_ERROR;
            return result;
        } catch (ConceptController.ConceptSaveException e) {
            Log.e(TAG, "Exception when trying to save concepts related to forms", e);
            result[0] = SAVE_ERROR;
            return result;
        } catch (ConceptController.ConceptDownloadException e) {
            Log.e(TAG, "Exception when trying to download concepts", e);
            result[0] = DOWNLOAD_ERROR;
        }
        return result;
    }

    private List<Concept> getRelatedConcepts(List<FormTemplate> formTemplates) throws ConceptController.ConceptDownloadException {
        HashSet<Concept> concepts = new HashSet<Concept>();
        ConceptParser utils = new ConceptParser();
        for (FormTemplate formTemplate : formTemplates) {
            List<String> names = utils.parse(formTemplate.getModel());
            concepts.addAll(conceptController.downloadConceptsByNames(names));
        }
        return new ArrayList<Concept>(concepts);
    }

    public int[] downloadCohorts() {
        int[] result = new int[2];
        try {

            List<Cohort> cohorts = downloadCohortsList();
            Log.i(TAG, "Cohort download successful");
            cohortController.deleteAllCohorts();
            Log.i(TAG, "Old cohorts are deleted");
            cohortController.saveAllCohorts(cohorts);
            Log.i(TAG, "New cohorts are saved");
            result[0] = SUCCESS;
            result[1] = cohorts.size();
        } catch (CohortController.CohortDownloadException e) {
            Log.e(TAG, "Exception when trying to download cohorts");
            result[0] = DOWNLOAD_ERROR;
            return result;
        } catch (CohortController.CohortSaveException e) {
            Log.e(TAG, "Exception when trying to save cohorts");
            result[0] = SAVE_ERROR;
            return result;
        } catch (CohortController.CohortDeleteException e) {
            Log.e(TAG, "Exception when trying to delete cohorts");
            result[0] = DELETE_ERROR;
            return result;
        }
        return result;
    }

    private List<Cohort> downloadCohortsList() throws CohortController.CohortDownloadException {
        List<String> cohortPrefixes = getCohortPrefixes();
        List<Cohort> cohorts;
        if (cohortPrefixes.isEmpty())
            cohorts = cohortController.downloadAllCohorts();
        else
            cohorts = cohortController.downloadCohortsByPrefix(cohortPrefixes);
        return cohorts;
    }

    public int[] downloadPatientsForCohorts(String[] cohortUuids) {
        int[] result = new int[3];

        int patientCount = 0;
        try {
            long startDownloadCohortData = System.currentTimeMillis();

            List<CohortData> cohortDataList = cohortController.downloadCohortData(cohortUuids);

            long endDownloadCohortData = System.currentTimeMillis();
            Log.i(TAG, "Cohort data download successful with " + cohortDataList.size() + " cohorts");

            for (String cohortUuid : cohortUuids) {
                cohortController.deleteCohortMembers(cohortUuid);
            }

            for (CohortData cohortData : cohortDataList) {
                cohortController.addCohortMembers(cohortData.getCohortMembers());
                patientController.replacePatients(cohortData.getPatients());
                patientCount += cohortData.getPatients().size();
            }
            long cohortMemberAndPatientReplaceTime = System.currentTimeMillis();

            Log.i(TAG, "Cohort data replaced");
            Log.i(TAG, "Patients downloaded " + patientCount);
            Log.d(TAG, "Time Taken:\n " +
                    "In Downloading cohort data: " + (endDownloadCohortData - startDownloadCohortData) / 1000 + " sec\n" +
                    "In Replacing cohort members and patients: " + (cohortMemberAndPatientReplaceTime - endDownloadCohortData) / 1000 + " sec");

            result[0] = SUCCESS;
            result[1] = patientCount;
            result[2] = cohortDataList.size();
        } catch (CohortController.CohortDownloadException e) {
            Log.e(TAG, "Exception thrown while downloading cohort data" + e);
            result[0] = DOWNLOAD_ERROR;
        } catch (CohortController.CohortReplaceException e) {
            Log.e(TAG, "Exception thrown while replacing cohort data" + e);
            result[0] = REPLACE_ERROR;
        } catch (PatientController.PatientReplaceException e) {
            Log.e(TAG, "Exception thrown while replacing patients" + e);
            result[0] = REPLACE_ERROR;
        }
        return result;
    }

    public int[] downloadObservationsForPatients(String[] cohortUuids) {
        int[] result = new int[2];

        ObservationController observationController = muzimaApplication.getObservationController();
        try {
            List<Patient> patients = patientController.getPatientsForCohorts(cohortUuids);
            List<String> patientUuids = getPatientUuids(patients);

            long startDownloadObservations = System.currentTimeMillis();
            List<Observation> allObservations = downloadAllObservations(patientUuids);
            long endDownloadObservations = System.currentTimeMillis();
            Log.i(TAG, "Observations download successful with " + allObservations.size() + " observations");

            observationController.replaceObservations(patientUuids, allObservations);
            long replacedObservations = System.currentTimeMillis();

            Log.d(TAG, "In Downloading observations : " + (endDownloadObservations - startDownloadObservations) / 1000 + " sec\n" +
                    "In Replacing observations for patients: " + (replacedObservations - endDownloadObservations) / 1000 + " sec");

            result[0] = SUCCESS;
            result[1] = allObservations.size();
        } catch (PatientController.PatientLoadException e) {
            Log.e(TAG, "Exception thrown while loading patients" + e);
            result[0] = LOAD_ERROR;
        } catch (ObservationController.DownloadObservationException e) {
            Log.e(TAG, "Exception thrown while downloading observations" + e);
            result[0] = DOWNLOAD_ERROR;
        } catch (ObservationController.ReplaceObservationException e) {
            Log.e(TAG, "Exception thrown while replacing observations" + e);
            result[0] = REPLACE_ERROR;
        }

        return result;
    }

    public int[] downloadEncountersForPatients(String[] cohortUuids) {
        int[] result = new int[2];

        PatientController patientController = muzimaApplication.getPatientController();
        EncounterController encounterController = muzimaApplication.getEncounterController();
        try {
            List<Patient> patients = patientController.getPatientsForCohorts(cohortUuids);
            List<String> patientUuids = getPatientUuids(patients);

            long startDownloadEncounters = System.currentTimeMillis();
            List<Encounter> allEncounters = downloadAllEncounters(patientUuids);
            long endDownloadObservations = System.currentTimeMillis();
            Log.i(TAG, "Encounters download successful with " + allEncounters.size() + " encounters");

            encounterController.replaceEncounters(patientUuids, allEncounters);
            long replacedEncounters = System.currentTimeMillis();

            Log.d(TAG, "In Downloading encounters : " + (endDownloadObservations - startDownloadEncounters) / 1000 + " sec\n" +
                    "In Replacing encounters for patients: " + (replacedEncounters - endDownloadObservations) / 1000 + " sec");

            result[0] = SUCCESS;
            result[1] = allEncounters.size();
        } catch (PatientController.PatientLoadException e) {
            Log.e(TAG, "Exception thrown while loading patients" + e);
            result[0] = LOAD_ERROR;
        } catch (EncounterController.DownloadEncounterException e) {
            Log.e(TAG, "Exception thrown while downloading encounters" + e);
            result[0] = DOWNLOAD_ERROR;
        } catch (EncounterController.ReplaceEncounterException e) {
            Log.e(TAG, "Exception thrown while replacing encounters" + e);
            result[0] = REPLACE_ERROR;
        }

        return result;
    }

    public int[] uploadAllCompletedForms() {
        int[] result = new int[1];
        try {
            result[0] = formController.uploadAllCompletedForms() ? SUCCESS : UPLOAD_ERROR;
        } catch (FormController.UploadFormDataException e) {
            Log.e(TAG, "Exception thrown while uploading forms " + e);
            result[0] = UPLOAD_ERROR;
        }
        return result;
    }

    private List<Observation> downloadAllObservations(List<String> patientUuids) throws ObservationController.DownloadObservationException {
        ObservationController observationController = muzimaApplication.getObservationController();
        return observationController.downloadObservationsByPatientUuidsAndConceptUuids(patientUuids, getConceptUuids());
    }

    private List<Encounter> downloadAllEncounters(List<String> patientUuids) throws EncounterController.DownloadEncounterException {
        EncounterController encounterController = muzimaApplication.getEncounterController();
        return encounterController.downloadEncountersByPatientUuids(patientUuids);
    }

    private List<String> getConceptUuids() {
        SharedPreferences cohortSharedPref = muzimaApplication.getSharedPreferences(Constants.CONCEPT_PREF, android.content.Context.MODE_PRIVATE);
        Set<String> prefixes = cohortSharedPref.getStringSet(Constants.CONCEPT_PREF_KEY, new HashSet<String>());
        return new ArrayList<String>(prefixes);

    }

    List<String> getPatientUuids(List<Patient> patients) {
        List<String> patientUuids = new ArrayList<String>();
        for (Patient patient : patients) {
            patientUuids.add(patient.getUuid());
        }
        return patientUuids;
    }

    private List<String> getCohortPrefixes() {
        ArrayList<String> result = new ArrayList<String>();
        SharedPreferences cohortSharedPref = muzimaApplication.getSharedPreferences(COHORT_PREFIX_PREF, android.content.Context.MODE_PRIVATE);
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            Set<String> prefixes = cohortSharedPref.getStringSet(COHORT_PREFIX_PREF_KEY, new HashSet<String>());
            result = new ArrayList<String>(prefixes);
        } else {
            int index = 1;
            String cohortPrefix = cohortSharedPref.getString(COHORT_PREFIX_PREF_KEY + index, null);
            while (cohortPrefix != null){
                result.add(cohortPrefix);
                index++;
                cohortPrefix = cohortSharedPref.getString(COHORT_PREFIX_PREF_KEY + index, null);
            }
        }
        return result;
    }


}
