package com.muzima.controller;

import com.muzima.api.model.CohortMember;
import com.muzima.api.model.Patient;
import com.muzima.api.model.PatientIdentifier;
import com.muzima.api.model.PatientIdentifierType;
import com.muzima.api.service.CohortService;
import com.muzima.api.service.PatientService;
import com.muzima.utils.Constants;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.queryParser.ParseException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

public class PatientControllerTest {

    private PatientController patientController;
    private PatientService patientService;
    private CohortService cohortService;

    @Before
    public void setup() {
        patientService = mock(PatientService.class);
        cohortService = mock(CohortService.class);
        patientController = new PatientController(patientService, cohortService);
    }

    @Test
    public void getAllPatients_shouldReturnAllAvailablePatients() throws IOException, ParseException, PatientController.PatientLoadException {
        List<Patient> patients = new ArrayList<Patient>();
        when(patientService.getAllPatients()).thenReturn(patients);

        assertThat(patientController.getAllPatients(), is(patients));
    }

    @Test(expected = PatientController.PatientLoadException.class)
    public void getAllForms_shouldThrowFormFetchExceptionIfExceptionThrownByFormService() throws IOException, ParseException, PatientController.PatientLoadException {
        doThrow(new IOException()).when(patientService).getAllPatients();
        patientController.getAllPatients();
    }

    @Test
    public void getTotalPatientsCount_shouldReturnPatientsCount() throws IOException, ParseException, PatientController.PatientLoadException {
        when(patientService.countAllPatients()).thenReturn(2);

        assertThat(patientController.getTotalPatientsCount(), is(2));
    }

    @Test
    public void replacePatients_shouldReplaceAllExistingPatientsAndAddNewPatients() throws IOException, PatientController.PatientSaveException {
        List<Patient> patients = buildPatients();

        patientController.replacePatients(patients);

        verify(patientService).updatePatients(patients);
        verifyNoMoreInteractions(patientService);
    }

    @Test(expected = PatientController.PatientSaveException.class)
    public void replacePatients_shouldThrowPatientReplaceExceptionIfExceptionThrownByService() throws IOException, PatientController.PatientSaveException {
        List<Patient> patients = buildPatients();

        doThrow(new IOException()).when(patientService).updatePatients(patients);

        patientController.replacePatients(patients);
    }

    @Test
    public void getPatientsInCohort_shouldReturnThePatientsInTheCohort() throws IOException, PatientController.PatientLoadException {
        String cohortId = "cohortId";
        List<CohortMember> members = buildCohortMembers(cohortId);
        when(cohortService.getCohortMembers(cohortId)).thenReturn(members);

        Patient patient = new Patient();
        patient.setUuid(members.get(0).getPatientUuid());
        when(patientService.getPatientsFromCohortMembers(members)).thenReturn(asList(patient));

        List<Patient> patients = patientController.getPatients(cohortId);

        assertThat(patients.size(), is(1));
    }

    @Test
    public void shouldSearchWithOutCohortUUIDIsNull() throws IOException, ParseException, PatientController.PatientLoadException {
        String searchString = "searchString";
        List<Patient> patients = new ArrayList<Patient>();

        when(patientService.searchPatients(searchString)).thenReturn(patients);

        assertThat(patientController.searchPatientLocally(searchString, null), is(patients));
        verify(patientService).searchPatients(searchString);

    }

    @Test
    public void shouldSearchWithOutCohortUUIDIsEmpty() throws IOException, ParseException, PatientController.PatientLoadException {
        String searchString = "searchString";
        List<Patient> patients = new ArrayList<Patient>();

        when(patientService.searchPatients(searchString)).thenReturn(patients);

        assertThat(patientController.searchPatientLocally(searchString, StringUtils.EMPTY), is(patients));
        verify(patientService).searchPatients(searchString);

    }

    @Test
    public void shouldCallSearchPatientWithCohortIDIfPresent() throws Exception, PatientController.PatientLoadException {
        String searchString = "searchString";
        String cohortUUID = "cohortUUID";
        List<Patient> patients = new ArrayList<Patient>();

        when(patientService.searchPatients(searchString, cohortUUID)).thenReturn(patients);
        assertThat(patientController.searchPatientLocally(searchString, cohortUUID), is(patients));

        verify(patientService).searchPatients(searchString, cohortUUID);
    }

    private List<CohortMember> buildCohortMembers(String cohortId) {
        List<CohortMember> cohortMembers = new ArrayList<CohortMember>();
        CohortMember member1 = new CohortMember();
        member1.setCohortUuid(cohortId);
        member1.setPatientUuid("patientId1");
        cohortMembers.add(member1);
        return cohortMembers;
    }

    @Test
    public void getPatientByUuid_shouldReturnPatientForId() throws Exception, PatientController.PatientLoadException {
        Patient patient = new Patient();
        String uuid = "uuid";

        when(patientService.getPatientByUuid(uuid)).thenReturn(patient);

        assertThat(patientController.getPatientByUuid(uuid), is(patient));
    }

    @Test
    public void shouldSearchOnServerForPatientByNames() throws Exception {
        String name = "name";
        List<Patient> patients = new ArrayList<Patient>();

        when(patientService.downloadPatientsByName(name)).thenReturn(patients);
        assertThat(patientController.searchPatientOnServer(name), is(patients));
        verify(patientService).downloadPatientsByName(name);
    }

    @Test
    @Ignore("Need to fix the Android Log class util")
    public void shouldReturnEmptyListIsExceptionThrown() throws Exception {
        String searchString = "name";
        doThrow(new IOException()).when(patientService).downloadPatientsByName(searchString);

        assertThat(patientController.searchPatientOnServer(searchString).size(), is(0));
    }

    @Test
    public void shouldGetAllLocalPatients() throws Exception, PatientController.PatientLoadException {
        Patient patientRemote1 = patient("remoteUUID1",null);
        Patient patientRemote2 = patient("remoteUUID2",null);
        Patient patientLocal = patient("localUUID1",patientIdentifier("localUUID1"));
        Patient localPatientButSyncedLater = patient("remoteUUID3",patientIdentifier("localUUID2"));

        when(patientService.getAllPatients()).thenReturn(asList(patientLocal,patientRemote1,patientRemote2,localPatientButSyncedLater));

        assertThat(patientController.getAllPatientsCreatedLocallyAndNotSynced().size(), is(1));
    }

    private Patient patient(String uuid, PatientIdentifier patientIdentifier) {
        Patient patient = new Patient();
        patient.setUuid(uuid);
        if (patientIdentifier != null) {
            patient.setIdentifiers(asList(patientIdentifier));
        }
        return patient;
    }

    private PatientIdentifier patientIdentifier(String identifier) {
        PatientIdentifier patientIdentifier = new PatientIdentifier();
        PatientIdentifierType identifierType = new PatientIdentifierType();
        identifierType.setName(Constants.LOCAL_PATIENT);
        patientIdentifier.setIdentifierType(identifierType);
        patientIdentifier.setIdentifier(identifier);
        return patientIdentifier;
    }

    @Test
    public void shouldConsolidatePatients() throws Exception {
        Patient tempPatient = mock(Patient.class);
        Patient patient = mock(Patient.class);
        when(patientService.consolidateTemporaryPatient(tempPatient)).thenReturn(patient);
        assertThat(patient, is(patientController.consolidateTemporaryPatient(tempPatient)));
    }

    private List<Patient> buildPatients() {
        ArrayList<Patient> patients = new ArrayList<Patient>();
        Patient patient1 = new Patient();
        patient1.setUuid("uuid1");
        Patient patient2 = new Patient();
        patient2.setUuid("uuid2");
        Patient patient3 = new Patient();
        patient3.setUuid("uuid3");
        patients.add(patient1);
        patients.add(patient2);
        patients.add(patient3);
        return patients;
    }
}
