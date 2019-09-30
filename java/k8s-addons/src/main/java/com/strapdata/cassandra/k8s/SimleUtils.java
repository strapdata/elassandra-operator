package com.strapdata.cassandra.k8s;

import com.datastax.driver.core.utils.Bytes;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class SimleUtils {

    private static final Logger log = LoggerFactory.getLogger(SimleUtils.class);

    public static byte[] convertToSmile(byte[] json, JsonFactory jsonFactory, SmileFactory smileFactory)
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        try // try-with-resources
                (
                        JsonGenerator jg = smileFactory.createGenerator(bos);
                        JsonParser jp = jsonFactory.createParser(json)
                )
        {
            while (jp.nextToken() != null)
            {
                jg.copyCurrentEvent(jp);
            }
        }
        catch (Exception e)
        {
            log.error("Error while converting json to smile", e);
        }

        return bos.toByteArray();
    }

    public static byte[] convertToJson(byte[] smile, JsonFactory jsonFactory, SmileFactory smileFactory)
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        try // try-with-resources
                (
                        JsonParser sp = smileFactory.createParser(smile);
                        JsonGenerator jg = jsonFactory.createGenerator(bos).useDefaultPrettyPrinter();
                )
        {
            while (sp.nextToken() != null)
            {
                jg.copyCurrentEvent(sp);
            }
        }
        catch (Exception e)
        {
            log.error("Error while converting smile to json", e);
        }

        return bos.toByteArray();
    }

    public static void main(String[] args) {
        try {
            ByteBuffer buf = Bytes.fromHexString("0x3a290a05fa83746f746ffa8676657273696f6e24a4847374617465436f70656e8773657474696e6773fa92696e6465782e6372656174696f6e5f646174654c3135363935333934333833393292696e6465782e70726f76696465645f6e616d6543746f746f89696e6465782e75756964555f52702d346e4c70536d53786d6d6833324d6c774b6794696e6465782e76657273696f6e2e637265617465644636303230333939fb876d617070696e6773f8fd029744464c00ac944b0ac2301040ef32eb2e6cb5557b19513a2d4171420c6a29b97b9382e001de6a98df63168f5924ce5e6be917f1c1bc86e8f455b2d1ac84d2955ea27ea354323a7d0c5bfbaef3c7c2f037f2ab54e2a6a705bd5c6ff6cebb4ddba594f2ae59bde391dbe9ec950d8fdcf3c8038f6c7964c7238f3cf284237987788578837881787f787d787bcea48ff9fbae000000ffff0300f986616c6961736573fafbfbfb" );
            String json = new String(convertToJson(Bytes.getArray(buf), new JsonFactory(), new SmileFactory()));
            System.out.println(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
