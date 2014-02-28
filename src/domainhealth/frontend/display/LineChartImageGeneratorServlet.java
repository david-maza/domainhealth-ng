//Copyright (C) 2008-2013 Paul Done . All rights reserved.
//This file is part of the DomainHealth software distribution. Refer to the  
//file LICENSE in the root of the DomainHealth distribution.
//THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
//AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
//IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
//ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE 
//LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
//CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
//SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
//INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
//CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
//ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
//POSSIBILITY OF SUCH DAMAGE.
package domainhealth.frontend.display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.StringTokenizer;

import javax.management.ObjectName;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import static javax.servlet.http.HttpServletResponse.*;

import static domainhealth.frontend.display.GraphScopedAttributeUtils.*;
import static domainhealth.frontend.display.HttpServletUtils.*;
import domainhealth.core.env.AppLog;
import domainhealth.core.env.AppProperties.PropKey;
import domainhealth.core.jmx.DomainRuntimeServiceMBeanConnection;
import domainhealth.core.jmx.WebLogicMBeanException;
import static domainhealth.core.jmx.WebLogicMBeanPropConstants.*;
import static domainhealth.core.statistics.StatisticsStorage.*;
import static domainhealth.core.statistics.MonitorProperties.*;
import static domainhealth.core.util.DateUtil.*;
import domainhealth.core.statistics.StatisticsStorage;
import domainhealth.core.util.DateUtil;
import domainhealth.frontend.data.DateAmountDataSet;
import domainhealth.frontend.graphics.JFreeChartGraphImpl;

/**
 * The servlet which generates the Line Graph Chart as a PNG image response.
 * Determines the type of resource, resource property and duration (time 
 * window) required to be displayed in the resulting graph and then uses the
 * JFreeChart open source library to generate the PNG byte output for the graph
 * as the servlet response.
 */
public class LineChartImageGeneratorServlet extends HttpServlet {
	/**
	 * Servlet initialiser which establishes the root path of the collected 
	 * statistics directories 
	 * 
	 * @throws ServletException Indicates that the root statistics path could not be established
	 *
	 * @see javax.servlet.GenericServlet#init()
	 */
	public void init() throws ServletException {
		statisticsStorage = new StatisticsStorage((String) getServletContext().getAttribute(PropKey.STATS_OUTPUT_PATH_PROP.toString()));
	}
	
	/**
	 * Forwards request to the process() method for main processing 
	 * 
	 * @param request The HTTP Servlet request
	 * @param response The HTTP Servlet response
	 * @throws ServletException Indicates a problem processing the HTTP servlet request
	 * @throws IOException Indicates a problem processing the HTTP servlet request
	 *
	 * @see domainhealth.display.LineChartServlet#processs(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */	
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		processs(request, response);
	}

	/**
	 * Forwards request to the process() method for main processing 
	 * 
	 * @param request The HTTP Servlet request
	 * @param response The HTTP Servlet response
	 * @throws ServletException Indicates a problem processing the HTTP servlet request
	 * @throws IOException Indicates a problem processing the HTTP servlet request
	 *
	 * @see domainhealth.display.LineChartServlet#processs(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */	
	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		processs(request, response);		
	}

	/**
	 * Processes the resource type, resource property, date-time, and duration 
	 * parameters, to establish the window for graph statistics to display, 
	 * retrieves the requires resource statistics from the relevant CSV file
	 * and then uses the JFreeChart open source library to generate a PNG 
	 * image based line chart based on these statistics. 
	 * 
	 * @param request The HTTP Servlet request
	 * @param response The HTTP Servlet response
	 * @throws ServletException Indicates a problem processing the HTTP servlet request
	 * @throws IOException Indicates a problem processing the HTTP servlet request
	 */
	private void processs(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			String resourceType = null;
			String resourceName = null;
			String resourceProperty = null;
			StringTokenizer pathTokens = new StringTokenizer(request.getPathInfo(), "" + URL_PATH_SEPERATOR);

			if (pathTokens.countTokens() <= 1) {
				response.sendError(SC_NOT_FOUND, "Unable to locate resource property for: " + request.getPathInfo());
				return;
			}
						
			int pos = 0;
			int numTokens = pathTokens.countTokens();
			
			while (pathTokens.hasMoreTokens()) {
				if (pos == 0) {
					resourceType = pathTokens.nextToken();
				} else if ((pos == 1) && (numTokens >= 3)) {
					resourceName = pathTokens.nextToken();
				} else if ((pos == 1) && (numTokens < 3)) {
					resourceProperty = pathTokens.nextToken();
				} else if (pos == 2) {
					resourceProperty = pathTokens.nextToken();
					break;
				}

				pos++;
			}			

			if (resourceType.equals(HOSTMACHINE_RESOURCE_TYPE)) {
				resourceName = HOST_MACHINE_MBEAN_NAME;
			}
			
			if ((resourceProperty == null) || ((!resourceType.equals(CORE_RESOURCE_TYPE)) && (resourceName == null))) {
				response.sendError(SC_NOT_FOUND, "Incorrect resource path elements specified to locate property for: " + request.getPathInfo());
				return;
			} else if (!LEGAL_RESOURCE_TYPES.contains(resourceType)) {
				response.sendError(SC_BAD_REQUEST, "Bad property type specified in resource path: " + request.getPathInfo());
				return;				
			}
			
			int suffixSeparator = resourceProperty.lastIndexOf(URL_SUFFIX_SEPERATOR);
			
			if (suffixSeparator > 1) {
				resourceProperty = resourceProperty.substring(0, suffixSeparator);
			}
			
			Date endDateTime = getDateTimeParam(request);					
			int durationMins = getDurationParam(request);
			String scope = getScopeParam(request);
			String title = title(resourceProperty);
			String units = units(resourceProperty);
			JFreeChartGraphImpl graph = new JFreeChartGraphImpl(title, units);
			int numServersDisplayed = addDataSeriesLines(graph, resourceType, resourceName, resourceProperty, endDateTime, durationMins, scope);
			response.setContentType(PNG_CONTENT_TYPE);
			response.setStatus(SC_OK);
			response.setHeader(PRAGMA_HEADER, NO_CACHE_HEADER_VALUE);
			response.setDateHeader(EXPIRES_HEADER, PAST_DATE_HEADER_VALUE); 
			response.setHeader(CACHE_CONTROL_HEADER, NO_CACHE_HEADER_VALUE);
			OutputStream out = response.getOutputStream();			
			graph.writeGraphImage(numServersDisplayed, out);
			out.flush();					
		} catch (IOException ioe) {
			// Swallow socket exceptions because usually they are just because user keeps pressing refresh/stop in browser
			if ((ioe.getCause() == null) || (!(ioe.getCause() instanceof SocketException))) {
				throw ioe;
			}
		} catch (WebLogicMBeanException we) {
			AppLog.getLogger().error("Error querying WebLogic DomainRuntime MBeans to retreive list of configured servers", we);
			throw new ServletException(we.toString(), we);
		}
	}

	/**
	 * Loops through each WebLogic server instance in the domain, collecting 
	 * the required statistics for the resource property for the server 
	 * instance and then adds the series of data-time/amount data values to 
	 * the graph for each of these servers.
	 * 
	 * @param graph The graph to add a line of data to
	 * @param resourceType The type of resource to plot (ie. core, data-source or destination)
	 * @param resourceName The name of the core/data-source/destination resource to plot
	 * @param resourceProperty The property of the core/data-source/destination resource to plot
	 * @param endDateTime The end date-time of the window of statistics to plot
	 * @param durationMins The duration of minutes of the window of statistics to plot
	 * @param serverScope The server scope (ALL or a specific named server)
	 * @return Number of servers to be displayed 
	 * @throws WebLogicMBeanException Indicates that the Admin Server JMX tree could not be queried
	 * @throws IOException Indicates a problem in collecting and processing the resource results
	 */
	private int addDataSeriesLines(JFreeChartGraphImpl graph, String resourceType, String resourceName, String resourceProperty, Date endDateTime, int durationMins, String serverScope) throws WebLogicMBeanException, IOException {
		if (serverScope.equals(ALL_SERVERS_SCOPE_VAL)) {		
			DomainRuntimeServiceMBeanConnection conn = null;
			
			try {
				conn = new DomainRuntimeServiceMBeanConnection();
				ObjectName domainConfig = conn.getDomainConfiguration();
				ObjectName[] servers = conn.getChildren(domainConfig, SERVERS);
				
				for (ObjectName server : servers) {
					String serverName = conn.getTextAttr(server, NAME);
					graph.addDataSeries(serverName, getPropertyData(resourceType, resourceName, resourceProperty, endDateTime, durationMins, serverName));
				}		
				
				return servers.length;
			} finally {
				if (conn != null) {
					conn.close();
				}
			}
		} else {
			graph.addDataSeries(serverScope, getPropertyData(resourceType, resourceName, resourceProperty, endDateTime, durationMins, serverScope));
			return 1;
		}
	}
	
	/**
	 * Collects the required statistics for a given server instance's resource
	 * property, adding the retrieved series of data-time/amount data values to 
	 * the graph for the server
	 * 
	 * @param endDateTime The end date-time of the window of statistics to plot
	 * @param durationMins The duration of minutes of the window of statistics to plot
	 * @param serverName The name of the WebLogic server to retrieve and process results for
	 * @param resourceType The type of resource to plot (ie. core, data-source or destination)
	 * @param resourceName The name of the core/data-source/destination resource to plot
	 * @param resourceProperty The property of the core/data-source/destination resource to plot
	 * @return The series of date-time/amount data items to be plotted as a line on a graph for a specific server  
	 * @throws IOException Indicates a problem in collecting and processing the resource results
	 */
	private DateAmountDataSet getPropertyData(String resourceType, String resourceName, String resourceProperty, Date endDateTime, int durationMins, String serverName) throws IOException {
		DateAmountDataSet resultDataSet = null;		
		Date startDateTime = DateUtil.getEarlierTime(endDateTime, durationMins);
		File file = statisticsStorage.getResourceStatisticsCSV(endDateTime, serverName, resourceType, resourceName);

		if ((file == null) || (!file.exists())) {
			return new DateAmountDataSet();
		}			

		int propertyPosition = statisticsStorage.getPropertyPositionInStatsFile(resourceType, resourceName, endDateTime, serverName, resourceProperty);
		
		if (propertyPosition < 0) {
			return new DateAmountDataSet();
		}

		BufferedReader in = null;
		
		try {
			in = new BufferedReader(new FileReader(file));
			resultDataSet = generatePropertyDataSet(in, startDateTime, endDateTime, propertyPosition);			
		} finally {
			if (in != null) {
				try { in.close(); } catch (Exception e) {}									
			}
		}

		return resultDataSet;
	}

	/**
	 * For a given property column in a CSV file, collects together all 
	 * statistic values for that property between a specific start and end date.
	 * 
	 * @param in The CSV file reader handle to read statisics from
	 * @param startDateTime The start date-time of the window of statistics to plot
	 * @param endDateTime The end date-time of the window of statistics to plot
	 * @param propertyPosition The colum position in the CSV file, for the property we want to get stats for
	 * @return The set of retrieved stats for the specific property within the specified time window
	 * @throws IOException Indicates that the statistics could not be retrieved properly from the CSV file
	 */
	private DateAmountDataSet generatePropertyDataSet(BufferedReader in, Date startDateTime, Date endDateTime, int propertyPosition) throws IOException {
		DateAmountDataSet resultDataSet = new DateAmountDataSet();
		DateFormat secondDateFormat = new SimpleDateFormat(DISPLAY_DATETIME_FORMAT);							
		Date dateTime = null;
		StringBuilder currentProperty = new StringBuilder();
		int positionCount = 0;
		int readChar = 0;
		char character = 0;
		boolean skipCurrentLine = true; 

		while ((readChar = in.read()) >= 0) {
			character = (char) readChar;

			if ((character == CRG_RETURN) || (character == NEW_LINE)) {
				skipCurrentLine = false;
				currentProperty = new StringBuilder();
				positionCount = 0;
			} else {	
				if (skipCurrentLine) {
					continue;
				} else if (character == SEPARATOR_CHAR) {
					if (positionCount == 0) {
						String dateTimeText = currentProperty.toString();
						
						try { 
							// Get first property as date-time
							dateTime = secondDateFormat.parse(dateTimeText);
						} catch(Exception e) {
							// Skip corrupted line
							skipCurrentLine = true;
							AppLog.getLogger().debug(getClass() + ".generatePropertyDataSet() skipping corrupt line when getting date-time. Cause: " + e.toString());  
						}
						
						if (dateTime.before(startDateTime)) {
							// Skip lines which are before current start date
							skipCurrentLine = true;
						} else if (dateTime.after(endDateTime)) {
							// Skip rest of file if after current end date
							break;
						}							
					} else if (positionCount == propertyPosition) {
						try {
							// Add found property
							double propValue = Double.parseDouble(currentProperty.toString());								
							resultDataSet.add(dateTime, propValue);
							skipCurrentLine = true;
						} catch (Exception e) {
							// Skip corrupted line
							AppLog.getLogger().debug(getClass() + ".generatePropertyDataSet() skipping corrupt line, propertyPosition=" + propertyPosition + ", dateTime=" + dateTime + ". Cause: " + e.toString());
							//System.out.println("CSV ERROR: " + getClass() + ".generatePropertyDataSet() skipping corrupt line, propertyPosition=" + propertyPosition + ", dateTime=" + dateTime + ". Cause: " + e.toString());
						}
					} 

					currentProperty = new StringBuilder();
					positionCount ++;				
				} else {
					currentProperty.append(character);
				}
			}
		}
		
		return resultDataSet;
	}

	// Members
	private StatisticsStorage statisticsStorage = null;	
	
	// Constants
	private static final long serialVersionUID = 1L;
}
