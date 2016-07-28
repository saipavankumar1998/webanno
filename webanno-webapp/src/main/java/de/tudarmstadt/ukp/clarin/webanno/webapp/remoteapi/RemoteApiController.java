/*******************************************************************************
 * Copyright 2012
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Resource;

import org.apache.commons.fileupload.InvalidFileNameException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.uima.UIMAException;
import org.apache.wicket.ajax.json.JSONArray;
import org.apache.wicket.ajax.json.JSONObject;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationService;
import de.tudarmstadt.ukp.clarin.webanno.api.RepositoryService;
import de.tudarmstadt.ukp.clarin.webanno.api.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.ZipUtils;
import de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.ProjectPermission;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.User;

/**
 * Expose some functions of WebAnno via a RESTful remote API.
 *
 */
@Controller
public class RemoteApiController
{
    public static final String MIME_TYPE_XML = "application/xml";
    public static final String PRODUCES_JSON = "application/json";
    public static final String PRODUCES_XML = "application/xml";
    public static final String CONSUMES_URLENCODED = "application/x-www-form-urlencoded";
    public static final String META_INF = "META-INF/";

    @Resource(name = "documentRepository")
    private RepositoryService projectRepository;

    @Resource(name = "annotationService")
    private AnnotationService annotationService;

    @Resource(name = "userRepository")
    private UserDao userRepository;

    private final Log LOG = LogFactory.getLog(getClass());

    /**
     * Create a new project.
     *
     * To test, use the Linux "curl" command.
     *
     * curl -v -F 'file=@test.zip' -F 'name=Test' -F 'filetype=tcf'
     * 'http://USERNAME:PASSWORD@localhost:8080/de.tudarmstadt.ukp.clarin.webanno.webapp/api/project
     * '
     *
     * @param aName
     *            the name of the project to create.
     * @param aFileType
     *            the type of the files contained in the ZIP. The possible file types are configured
     *            in the formats.properties configuration file of WebAnno.
     * @param aFile
     *            a ZIP file containing the project data.
     * @throws Exception if there was en error.
     */
    @RequestMapping(value = "/project", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public @ResponseStatus(HttpStatus.NO_CONTENT)
    void createProject(@RequestParam("file") MultipartFile aFile,
            @RequestParam("name") String aName, @RequestParam("filetype") String aFileType)
        throws Exception
    {
        LOG.info("Creating project [" + aName + "]");

        if (!ZipUtils.isZipStream(aFile.getInputStream())) {
            throw new InvalidFileNameException("", "is an invalid Zip file");
        }

        // Get current user
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.get(username);
        Project project = null;

        // Configure project
        if (!projectRepository.existsProject(aName)) {
            project = new Project();
            project.setName(aName);

            // Create the project and initialize tags
            projectRepository.createProject(project, user);
            annotationService.initializeTypesForProject(project, user);
            // Create permission for this user
            ProjectPermission permission = new ProjectPermission();
            permission.setLevel(PermissionLevel.ADMIN);
            permission.setProject(project);
            permission.setUser(username);
            projectRepository.createProjectPermission(permission);

            permission = new ProjectPermission();
            permission.setLevel(PermissionLevel.USER);
            permission.setProject(project);
            permission.setUser(username);
            projectRepository.createProjectPermission(permission);
        }
        // Existing project
        else {
            throw new IOException("The project with name [" + aName + "] exists");
        }

        // Iterate through all the files in the ZIP

        // If the current filename does not start with "." and is in the root folder of the ZIP,
        // import it as a source document
        File zimpFile = File.createTempFile(aFile.getOriginalFilename(), ".zip");
        aFile.transferTo(zimpFile);
        ZipFile zip = new ZipFile(zimpFile);

        for (Enumeration<?> zipEnumerate = zip.entries(); zipEnumerate.hasMoreElements();) {
            //
            // Get ZipEntry which is a file or a directory
            //
            ZipEntry entry = (ZipEntry) zipEnumerate.nextElement();

            // If it is the zip name, ignore it
            if ((FilenameUtils.removeExtension(aFile.getOriginalFilename()) + "/").equals(entry
                    .toString())) {
                continue;
            }
            // IF the current filename is META-INF/webanno/source-meta-data.properties store it
            // as
            // project meta data
            else if (entry.toString().replace("/", "")
                    .equals((META_INF + "webanno/source-meta-data.properties").replace("/", ""))) {
                InputStream zipStream = zip.getInputStream(entry);
                projectRepository.savePropertiesFile(project, zipStream, entry.toString());

            }
            // File not in the Zip's root folder OR not
            // META-INF/webanno/source-meta-data.properties
            else if (StringUtils.countMatches(entry.toString(), "/") > 1) {
                continue;
            }
            // If the current filename does not start with "." and is in the root folder of the
            // ZIP, import it as a source document
            else if (!FilenameUtils.getExtension(entry.toString()).equals("")
                    && !FilenameUtils.getName(entry.toString()).equals(".")) {

                uploadSourceDocument(zip, entry, project, user, aFileType);
            }

        }

        LOG.info("Successfully created project [" + aName + "] for user [" + username + "]");

    }

    /**
     * List all the projects for a given user with their roles
     * 
     * Test: Open your browser, paste following URL with appropriate values for username and
     * password:
     * 
     * http://USERNAME:PASSWORD@localhost:8080/de.tudarmstadt.ukp.clarin.webanno
     * .webapp/api/project/list
     * 
     * @return JSON string of project where user has access to and respective roles in the project
     * @throws Exception
     *             if there was en error.
     */
    @RequestMapping(value = "/project/list", method = RequestMethod.GET)
    public @ResponseBody String listProject()
        throws Exception
    {
        List<Project> accessibleProjects;
        JSONObject returnJSONObj = new JSONObject();

        // Get username
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        LOG.info("Fetch project list for [" + username + "]");
        User user = userRepository.get(username);

        // Get projects with permission
        accessibleProjects = projectRepository.listAccessibleProjects();

        // Add permissions for each project into JSON array and store in json
        // object
        for (Project project : accessibleProjects) {
            String projectId = Long.toString(project.getId());
            List<ProjectPermission> projectPermissions = projectRepository
                    .listProjectPermisionLevel(user, project);
            JSONArray permissionArr = new JSONArray();
            JSONObject projectJSON = new JSONObject();

            for (ProjectPermission p : projectPermissions) {
                permissionArr.put(p.getLevel().getName().toString());
            }
            projectJSON.put(project.getName(), permissionArr);
            returnJSONObj.put(projectId, projectJSON);
        }
        return returnJSONObj.toString();
    }

    /**
     * Delete a project where user has an admin role
     * 
     * To test, use the Linux "curl" command.
     * 
     * curl -v -F 'name=PROJECTNAME'
     * 'http://USERNAME:PASSWORD@localhost:8080/de.tudarmstadt.ukp.clarin.
     * webanno.webapp/api/project/delete'
     * 
     * @param aName
     *            The name of the project
     * @return success in ResponseBody if project is deleted
     * @throws Exception
     *             if there was en error.
     */
    @RequestMapping(value = "/project/delete", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public @ResponseStatus(HttpStatus.NO_CONTENT) void deleteProject(
            @RequestParam("name") String aName)
                throws Exception
    {
        LOG.info("Deleting project [" + aName + "]");

        // Get current user
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.get(username);
        Project project = null;

        if (projectRepository.existsProject(aName)) {
            // Get project
            project = projectRepository.getProject(aName);
        }
        else {
            throw new IOException("The project with name [" + aName + "] does not exists");
        }
        // Check for the access
        boolean hasAccess = projectRepository.existsProjectPermissionLevel(user, project,
                PermissionLevel.ADMIN);

        if (hasAccess) {
            // remove project is user has admin access
            projectRepository.removeProject(project, user);
            LOG.info("Successfully deleted project [" + aName + "]");
        }
        else {
            throw new PermissionDeniedDataAccessException(
                    "Not enough permission on [" + aName + "]", new Throwable("user:" + username));
        }

    }

    /**
     * Delete the source document in project if user has ADMIN permissions
     * 
     * To test, use the Linux "curl" command.
     * 
     * curl -v -F 'projectname=PROJECTNAME' -F 'documentname=DOCUMENTNAME'
     * 'http://USERNAME:PASSWORD@localhost:8080/de.tudarmstadt.ukp.clarin.
     * webanno.webapp/api/project/delete/sourcedocument'
     * 
     * @param aName
     *            Project Name
     * @param docName
     *            Source Document name
     * @throws Exception
     *             if there was en error.
     */
    @RequestMapping(value = "/project/delete/sourcedocument", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public @ResponseStatus(HttpStatus.NO_CONTENT) void deleteDocument(
            @RequestParam("projectname") String aName, @RequestParam("documentname") String docName)
                throws Exception
    {
        LOG.info("Deleting document [" + aName + "]");
        // Get current user
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.get(username);
        Project project = null;

        if (projectRepository.existsProject(aName)) {
            // Get project
            project = projectRepository.getProject(aName);
        }
        else {
            throw new IOException("The project with name [" + aName + "] does not exists");
        }

        // Check for the access
        boolean hasAccess = projectRepository.existsProjectPermissionLevel(user, project,
                PermissionLevel.ADMIN);

        // Check if document with the name is present or not
        boolean isDocumentPresent = projectRepository.existsSourceDocument(project, docName);

        // remove document if hasAccess and document is present
        if (hasAccess && isDocumentPresent) {
            SourceDocument document = projectRepository.getSourceDocument(project, docName);
            projectRepository.removeSourceDocument(document);
            LOG.info("Successfully deleted project [" + aName + "]");
        }
        else {
            if (!hasAccess) {
                throw new PermissionDeniedDataAccessException(
                        "Not enough permission on [" + aName + "]",
                        new Throwable("user:" + username));
            }
            else {
                throw new IOException("Document with name [" + docName + "] not present");
            }
        }
    }

    /**
     * Upload a source document into project where user has "ADMIN" role
     * 
     * Test: curl -v -F 'file=@test.txt' -F 'name=Test' -F 'filetype=txt'
     * 'http://USERNAME:PASSWORD@localhost:8080/de.tudarmstadt.ukp.clarin.
     * webanno.webapp/api/project/upload/sourcedocument'
     * 
     * 
     * @param aFile
     *            File
     * @param aName
     *            Project Name
     * @param aFileType
     *            Extension of the file
     * @throws Exception
     *             if there was en error.
     */
    @RequestMapping(value = "/project/upload/sourcedocument", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public @ResponseStatus(HttpStatus.NO_CONTENT) void uploadDocumentFile(
            @RequestParam("file") MultipartFile aFile, @RequestParam("name") String aName,
            @RequestParam("filetype") String aFileType)
                throws Exception
    {

        // Get current user
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.get(username);

        // Get project
        Project project = null;

        if (projectRepository.existsProject(aName)) {
            // Get project
            project = projectRepository.getProject(aName);
        }
        else {
            throw new IOException("The project with name [" + aName + "] does not exists");
        }

        // Check for the access
        boolean hasAccess = projectRepository.existsProjectPermissionLevel(user, project,
                PermissionLevel.ADMIN);

        if (hasAccess) {
            File uploadDocumentFile = new File(aFile.getOriginalFilename());
            aFile.transferTo(uploadDocumentFile);

            // Check if file already present or not
            boolean isDocumentPresent = projectRepository.existsSourceDocument(project,
                    aFile.getOriginalFilename());
            if (!isDocumentPresent)
                uploadSourceDocumentFile(uploadDocumentFile, project, user, aFileType);
            else {
                throw new IOException("The source document with name ["
                        + aFile.getOriginalFilename() + "] exists");
            }
        }
        else {
            throw new PermissionDeniedDataAccessException("Not ADMIN permission on [" + aName + "]",
                    new Throwable("user:" + username));
        }
    }

    private void uploadSourceDocumentFile(File file, Project project, User user, String aFileType)
            throws IOException, UIMAException {

        FileInputStream fs = new FileInputStream(file);

        // Check if it is a property file
        if (file.getName().equals("source-meta-data.properties")) {
            projectRepository.savePropertiesFile(project, fs, file.getName());
        } else {
            SourceDocument document = new SourceDocument();
            document.setName(file.getName());
            document.setProject(project);
            document.setFormat(aFileType);
            // Meta data entry to the database
            projectRepository.createSourceDocument(document, user);
            // Import source document to the project repository folder
            projectRepository.uploadSourceDocument(fs, document);
        }

    }
    private void uploadSourceDocument(ZipFile zip, ZipEntry entry, Project project, User user,
            String aFileType)
        throws IOException, UIMAException
    {
        String fileName = FilenameUtils.getName(entry.toString());

        InputStream zipStream = zip.getInputStream(entry);
        SourceDocument document = new SourceDocument();
        document.setName(fileName);
        document.setProject(project);
        document.setFormat(aFileType);
        // Meta data entry to the database
        projectRepository.createSourceDocument(document, user);
        // Import source document to the project repository folder
        projectRepository.uploadSourceDocument(zipStream, document);
    }
}
