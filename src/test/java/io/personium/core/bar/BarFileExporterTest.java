/**
 * Personium
 * Copyright 2019 Personium Project Authors
 *  - Akio Shimono
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
package io.personium.core.bar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.json.Json;
import javax.json.JsonObject;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import io.personium.common.utils.CommonUtils;
import io.personium.core.PersoniumUnitConfig;
import io.personium.core.auth.AccessContext;
import io.personium.core.model.Box;
import io.personium.core.model.BoxRsCmp;
import io.personium.core.model.Cell;
import io.personium.core.model.CellRsCmp;
import io.personium.core.model.DavCmp;
import io.personium.core.model.impl.es.CellEsImpl;
import io.personium.core.model.impl.fs.DavCmpFsImplTest.MockDavCmpFsImpl;
import io.personium.core.model.jaxb.Ace;
import io.personium.core.model.jaxb.Acl;
import io.personium.core.utils.TestUtils;
import io.personium.core.utils.UriUtils;

/**
 * Unit tests for BarFileExporter class.
 */
public class BarFileExporterTest {
    public static String CELL_URL;
    public static String BOX_SCHEMA_URL;
    public static final String BOX_NAME = "box";

    public static byte[] barFileBytes;
    public static List<ZipEntry> barZipEntryList;
    public static Map<String, ZipEntry> barZipEntryMap;
    public static Map<String, byte[]> barZipContentMap;
    static Logger log = LoggerFactory.getLogger(BarFileExporterTest.class);

    /**
     * Set Personium Unit configuration for the testing.
     *   Unit url = https://unit.example/
     *   Path-based Cell URL = false
     */
    @BeforeClass
    public static void beforeClass() {
        PersoniumUnitConfig.set(PersoniumUnitConfig.PATH_BASED_CELL_URL_ENABLED, "false");
        CommonUtils.setFQDN("unit.example");
        PersoniumUnitConfig.set(PersoniumUnitConfig.UNIT_PORT, "");
        PersoniumUnitConfig.set(PersoniumUnitConfig.UNIT_SCHEME, "https");
        CELL_URL = UriUtils.convertSchemeFromLocalUnitToHttp("personium-localunit:user1:/");
        BOX_SCHEMA_URL = UriUtils.convertSchemeFromLocalUnitToHttp("personium-localunit:app1:/");

        prepareBarFile();
    }
    /**
     * Reset Personium Unit configuration.
     */
    @AfterClass
    public static void afterClass() throws Exception {
        PersoniumUnitConfig.reload();
    }


    public static Cell mockCell(String cellUrl) {
        return new CellEsImpl() {
            @Override
            public String getUrl() {
                return cellUrl;
            };
        };
    }
    public static Box mockBox(Cell cell, String boxName, String boxSchemaUrl) {
        return new Box(cell, boxName, boxSchemaUrl, UUID.randomUUID().toString() , TestUtils.DATE_PUBLISHED.getTime());
    }
    public static Acl mockAcl(Box box, Map<String, List<String>> aclSettings) {
        Acl acl =  new Acl();
        acl.setBase(box.getCell().getUrl() + box.getName() + "/");

        for (String href : aclSettings.keySet()) {
            List<String> privilegeList = aclSettings.get(href);
            Ace ace = new Ace();
            ace.setPrincipalHref(href);
            for (String priv : privilegeList) {
                ace.addGrantedPrivilege(priv);
            }
            acl.getAceList().add(ace);
        }
        return acl;
    }
    public static BoxRsCmp mockBoxRsComp(Box box) {
        // Test Settings

        // prepare ACL
        Map<String, List<String>> aclSettings = new HashMap<>();
        List<String> grantList = new ArrayList<>();
        grantList.add("read");
        aclSettings.put("role1", grantList);
        Acl acl1 = mockAcl(box, aclSettings);

        // Prepare DavCmp structure
        MockDavCmpFsImpl dcCell = new MockDavCmpFsImpl(box.getCell(), null);
        MockDavCmpFsImpl dcBox = new MockDavCmpFsImpl(box, dcCell, acl1);
        // box/col/file2.json
        // box/file1.json
        MockDavCmpFsImpl col = new MockDavCmpFsImpl("col", dcBox, acl1);
        new MockDavCmpFsImpl("file1.json", col, acl1, DavCmp.TYPE_DAV_FILE);
        new MockDavCmpFsImpl("file2.json", dcBox, null, DavCmp.TYPE_DAV_FILE);

        AccessContext ac = null;
        CellRsCmp cellCmp = new CellRsCmp(dcCell, box.getCell(), ac);
        return new BoxRsCmp(cellCmp, dcBox, ac, box);
    }

    public static byte[] responseToBytes(StreamingOutput so) {
        byte [] byteArray = null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()){
            so.write(baos);
            byteArray = baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return byteArray;
    }
    public static void readExportedBarFile(byte[] barBytes) {
        barZipEntryList = new ArrayList<>();
        barZipEntryMap = new HashMap<>();
        barZipContentMap = new HashMap<>();
        try (
            ByteArrayInputStream bais = new ByteArrayInputStream(barBytes);
            ZipInputStream zis = new ZipInputStream(bais)
        ){
            ZipEntry ent = null;
            int bufferSize = 1024;
            while((ent = zis.getNextEntry()) != null) {
                String entName = ent.getName();
                log.info(entName);

                barZipEntryList.add(ent);
                barZipEntryMap.put(entName, ent);

                byte data[] = new byte[bufferSize];
                int count = 0;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                while((count = zis.read(data, 0, bufferSize)) != -1) {
                    baos.write(data,0,count);
                }

                barZipEntryMap.put(entName, ent);
                barZipContentMap.put(entName, baos.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void prepareBarFile() {
        // prepare mocks for testing
        Cell cell = mockCell(CELL_URL);
        Box box = mockBox(cell, BOX_NAME, BOX_SCHEMA_URL);
        BoxRsCmp boxRsCmpMock = mockBoxRsComp(box);

        // create bar file response
        BarFileExporter exporter =  new BarFileExporter(boxRsCmpMock);
        //  ... this part is the core of this unit test ...
        Response res = exporter.export();

        // parse the response bar file contents
        StreamingOutput so = (StreamingOutput)res.getEntity();
        byte[] barFileBytes = responseToBytes(so);
        readExportedBarFile(barFileBytes);
    }

    @Before
    public void before() throws Exception {
    }

    /**
     * bar file composition check.
     */
    @Test
    public void export_BarFile_ShouldInclude_NecessaryZipEntries() {
        ZipEntry meta = barZipEntryMap.get("00_meta/");
        assertNotNull(meta);
        assertTrue(meta.isDirectory());

        ZipEntry manifest = barZipEntryMap.get("00_meta/00_manifest.json");
        assertNotNull(manifest);
        assertFalse(manifest.isDirectory());

        ZipEntry rootprops = barZipEntryMap.get("00_meta/90_rootprops.xml");
        assertNotNull(rootprops);
        assertFalse(rootprops.isDirectory());

        ZipEntry contents = barZipEntryMap.get("90_contents/");
        assertNotNull(contents);
        assertTrue(contents.isDirectory());

        ZipEntry col = barZipEntryMap.get("90_contents/col/");
        assertNotNull(col);
        assertTrue(col.isDirectory());

        ZipEntry f1 = barZipEntryMap.get("90_contents/col/file1.json");
        assertNotNull(f1);
        assertFalse(f1.isDirectory());

        ZipEntry f2 = barZipEntryMap.get("90_contents/file2.json");
        assertNotNull(f2);
        assertFalse(f2.isDirectory());

    }

    /**
     * check for 90_rootprops.xml in the exported bar file.
     * @throws Exception
     */
    @Test
    public void export_RootpropsXml_ShouldHave_ValidContents() throws Exception {
        byte[] b = barZipContentMap.get("00_meta/90_rootprops.xml");
        String rootpropsXml = new String(b);
        log.info("00_meta/90_rootprops.xml\n----\n" + rootpropsXml + "\n----");

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document doc = db.parse(new InputSource(new StringReader(rootpropsXml)));
        XPath xpath = XPathFactory.newInstance().newXPath();

        // ACL base Url should be the base url of Role Class Url
        String base = xpath.evaluate("//acl[position()=1]/@base", doc);
        log.info("//acl[position()=1]/@base = " + base);
        assertEquals(BOX_SCHEMA_URL + "__role/__/", base);

        // href url should use personium-localbox: scheme
        String href = xpath.evaluate("//href[position()=1]/text()", doc);
        log.info("//href[position()=1]/text() = " + href);
        assertEquals("personium-localbox:/", href);
    }

    /**
     * check for 00_manifest.json in the exported bar file.
     * @throws Exception
     */
    @Test
    public void export_ManifestJson_ShouldHave_ValidContents() throws Exception {
        byte[] b = barZipContentMap.get("00_meta/00_manifest.json");
        String manifestJson = new String(b);
        log.info("00_meta/00_manifest.json\n----\n" + manifestJson + "\n----");

        JsonObject json = Json.createReader(new StringReader(manifestJson)).readObject();

        // Box schema URL should be in "shema" key.
        assertEquals(BOX_SCHEMA_URL, json.getString("schema"));

        // bar version should be "2".
        assertEquals("2", json.getString("bar_version"));
    }
}
