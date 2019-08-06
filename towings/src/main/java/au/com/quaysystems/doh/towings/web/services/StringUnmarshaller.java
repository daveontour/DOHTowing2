package au.com.quaysystems.doh.towings.web.services;

import java.io.IOException;
import java.io.StringWriter;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.oxm.Unmarshaller;
import org.springframework.oxm.XmlMappingException;public class StringUnmarshaller implements Unmarshaller {
	
	public static final TransformerFactory tfactory = TransformerFactory.newInstance();
	public static final Transformer xform;
	static {
		try {
			xform = tfactory.newTransformer();
		} catch (TransformerConfigurationException e) {
			// TODO Auto-generated catch block
			throw new RuntimeException(e);
		} // end try/catch
	}

	@Override
	public boolean supports(Class<?> clazz) {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	public Object unmarshal(Source source) throws IOException, XmlMappingException {
		return convertDom((DOMSource)source);
	}
	
	public String convertDom( DOMSource src ) {
		StringWriter writer = new StringWriter();
		Result result = new StreamResult(writer);
		// Finally use your empty transform to read from the source (your XML document in DOM format), apply a transform (a do nothing transform) and write the result (to your StreamResult which in turn is based on a StringWriter).
		try {
			xform.transform(src, result);
		} catch (TransformerException e) {
			e.printStackTrace();
		} // end try/catch
		
		// We can now extract the DOM as a text string by using the toString method on the StringWriter that we created.
		return writer.toString();
	} 

}
