/*
 * Copyright (C) 2005-2007 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have recieved a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing"
 */
package org.alfresco.web.api.framework;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.alfresco.i18n.I18NUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.transaction.TransactionUtil;
import org.alfresco.service.transaction.TransactionService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;


/**
 * Stand-alone Web API Test Server
 * 
 * @author davidc
 */
public class TestAPIServer
{
    // dependencies
    protected TransactionService transactionService;
    protected DeclarativeAPIRegistry apiRegistry;
    
    /** The reader for interaction. */
    private BufferedReader fIn;
    
    /** Last command issued */
    private String lastCommand = null;

    /** Current user */
    private String username = "admin";
    
    
    /**
     * Sets the transaction service
     * 
     * @param transactionService
     */
    public void setTransactionService(TransactionService transactionService)
    {
        this.transactionService = transactionService;
    }
    
    /**
     * Sets the API Registry
     * 
     * @param apiRegistry
     */
    public void setAPIRegistry(DeclarativeAPIRegistry apiRegistry)
    {
        this.apiRegistry = apiRegistry;
    }
    
    
    /**
     * Initialise the Test API Server
     * 
     * @throws Exception
     */
    public void init() throws Exception
    {
        apiRegistry.initServices();
        fIn = new BufferedReader(new InputStreamReader(System.in));
    }
    
    /**
     * Main entry point.
     */
    public static void main(String[] args)
    {
        try
        {
            String[] CONFIG_LOCATIONS = new String[] { "classpath:alfresco/application-context.xml", "classpath:alfresco/web-api-application-context.xml", "classpath:alfresco/web-api-application-context-test.xml" };
            ApplicationContext context = new ClassPathXmlApplicationContext(CONFIG_LOCATIONS);
            TestAPIServer testServer = (TestAPIServer)context.getBean("web.api.framework.test");
            testServer.init();
            testServer.rep();
        }
        catch(Throwable e)
        {
            StringWriter strWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(strWriter);
            e.printStackTrace(printWriter);
            System.out.println(strWriter.toString());
        }
        finally
        {
            System.exit(0);
        }
    }
    
    /**
     * A Read-Eval-Print loop.
     */
    public void rep()
    {
        // accept commands
        while (true)
        {
            System.out.print("ok> ");
            try
            {
                // get command
                final String line = fIn.readLine();
                if (line.equals("exit") || line.equals("quit"))
                {
                    return;
                }
                
                // execute command in context of currently selected user
                long startms = System.currentTimeMillis();
                System.out.print(interpretCommand(line));
                System.out.println("" + (System.currentTimeMillis() - startms) + "ms");
            }
            catch (Exception e)
            {
                e.printStackTrace(System.err);
                System.out.println("");
            }
        }
    }
    
    /**
     * Interpret a single command using the BufferedReader passed in for any data needed.
     * 
     * @param line The unparsed command
     * @return The textual output of the command.
     */
    public String interpretCommand(final String line)
        throws IOException
    {
        // execute command in context of currently selected user
        return AuthenticationUtil.runAs(new RunAsWork<String>()
        {
            public String doWork() throws Exception
            {
                return executeCommand(line);
            }
        }, username);
    }
    
    /**
     * Execute a single command using the BufferedReader passed in for any data needed.
     * 
     * TODO: Use decent parser!
     * 
     * @param line The unparsed command
     * @return The textual output of the command.
     */
    protected String executeCommand(String line)
        throws IOException
    {
        String[] command = line.split(" ");
        if (command.length == 0)
        {
            command = new String[1];
            command[0] = line;
        }
        
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bout);

        // repeat last command?
        if (command[0].equals("r"))
        {
            if (lastCommand == null)
            {
                return "No command entered yet.";
            }
            return "repeating command " + lastCommand + "\n\n" + interpretCommand(lastCommand);
        }
        
        // remember last command
        lastCommand = line;

        // execute command
        if (command[0].equals("help"))
        {
            // TODO:
            String helpFile = I18NUtil.getMessage("test_service.help");
            ClassPathResource helpResource = new ClassPathResource(helpFile);
            byte[] helpBytes = new byte[500];
            InputStream helpStream = helpResource.getInputStream();
            try
            {
                int read = helpStream.read(helpBytes);
                while (read != -1)
                {
                    bout.write(helpBytes, 0, read);
                    read = helpStream.read(helpBytes);
                }
            }
            finally
            {
                helpStream.close();
            }
        }
        
        else if (command[0].equals("user"))
        {
            if (command.length == 2)
            {
                username = command[1];
            }
            out.println("using user " + username);
        }
        
        else if (command[0].equals("get"))
        {
            if (command.length < 2)
            {
                return "Syntax Error.\n";
            }

            String uri = command[1];
            MockHttpServletRequest req = createRequest("get", uri);
            MockHttpServletResponse res = new MockHttpServletResponse();
            
            APIServiceMatch match = apiRegistry.findService(req.getMethod(), uri);
            if (match == null)
            {
                throw new APIException("No service bound to uri '" + uri + "'");
            }

            APIRequest apiReq = new APIRequest(req, match);
            APIResponse apiRes = new APIResponse(res);
            match.getService().execute(apiReq, apiRes);
            bout.write(res.getContentAsByteArray());
            out.println();
        }
        
        else
        {
            return "Syntax Error.\n";
        }
 
        out.flush();
        String retVal = new String(bout.toByteArray());
        out.close();
        return retVal;
    }

    /**
     * Create a Mock HTTP Servlet Request
     * 
     * @param method
     * @param uri
     * @return  mock http servlet request
     */
    private MockHttpServletRequest createRequest(String method, String uri)
    {
        MockHttpServletRequest req = new MockHttpServletRequest("get", uri);

        // set parameters
        int iArgIndex = uri.indexOf('?');
        if (iArgIndex != -1 && iArgIndex != uri.length() -1)
        {
            String uriArgs = uri.substring(iArgIndex +1);
            String[] args = uriArgs.split("&");
            for (String arg : args)
            {
                String[] parts = arg.split("=");
                req.addParameter(parts[0], (parts.length == 2) ? parts[1] : null);
            }
        }
        
        // set path info
        req.setPathInfo(iArgIndex == -1 ? uri : uri.substring(0, iArgIndex));

        // set servlet path
        req.setServletPath("/alfresco/service");
        
        return req;
    }
    
}
