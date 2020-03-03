package au.com.quaysystems.doh.tow;

import java.io.File;
import java.io.FileInputStream;

/*
 * 
 * Production Release RC 3.7
 * 
 */

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet implementation class Test
 */
public class TowInfo extends HttpServlet {
	private static final long serialVersionUID = 1L;
       
    /**
     * @see HttpServlet#HttpServlet()
     */
    public TowInfo() {
        super();
        // TODO Auto-generated constructor stub
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		   String result = "Tow Running - Unable to Display Properties";

			try {
				Properties props = getProperties();
				result = "Towing Interface **** Version 3.7 **** Properties: "+props.toString();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
	      response.setContentType("text/plain");
	      response.getWriter().write(result);
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doGet(request, response);
	}
	
//	public Properties getProperties() throws IOException {
//		 
//		InputStream inputStream = null;
//		Properties props = new Properties();
//
//		try {
//			String propFileName = "application.properties";
// 
//			inputStream = getClass().getClassLoader().getResourceAsStream(propFileName);
// 
//			if (inputStream != null) {
//				props.load(inputStream);
//			} else {
//				throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
//			}
// 
// 		} catch (Exception e) {
//			System.out.println("Exception: " + e);
//		} finally {
//			inputStream.close();
//		}
//		return props;
//	}
	
	public Properties getProperties() throws IOException {


		InputStream inputStream = null;
		Properties props = new Properties();

		try {
			String propFileName = "application.properties";

			inputStream = getClass().getClassLoader().getResourceAsStream(propFileName);

			if (inputStream != null) {
				props.load(inputStream);
			} else {
				try {
					File initialFile = new File("C:/Users/dave_/Desktop/application.properties");
					inputStream = new FileInputStream(initialFile);
					props.load(inputStream);
				} catch (Exception e) {
					throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
				}
			}

		} catch (Exception e) {
			System.out.println("Exception: " + e);
		} finally {
			inputStream.close();
		}
		return props;

	}

}
