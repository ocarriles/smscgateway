package org.mobicents.protocols.smpp.callback;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.mobicents.protocols.smpp.Server;
import org.mobicents.protocols.smpp.SmppManagementProxy;
import org.mobicents.protocols.smpp.timers.ActivityTimeoutSmppSessionHandler;
import org.restcomm.smpp.Esme;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class RequestSenderQueueCallbackTest {
    private static Logger logger = Logger.getLogger(RequestSenderQueueCallbackTest.class);

    private SmppManagementProxy smppManagementProxy;

    private static final int NUM_RECEIVER_ESMES = 2;
    private Esme[] esmes;

    private Server[] servers;

    private String esmeNamePref = "callback_";
    private String systemId = "callback";
    private String password = "password";
    private String localAddress = "127.0.0.1";
    private int localPort = 44444;

    String[] addressRanges = new String[] { "1[0-9][0-9][0-9]", "2[0-9][0-9][0-9]", "2[0-9][0-9][0-9]" };
    String senderNumber = "1001";
    String[] receiverNumbers = new String[] { "2001", "2002", "2003" };

    private String message = "Hi======";

    private ScheduledThreadPoolExecutor monitorExecutor;

    @BeforeClass
    public void setUpClass() throws Exception {
        logger.info("setUpClass");

        smppManagementProxy = new SmppManagementProxy();

        esmes = new Esme[NUM_RECEIVER_ESMES + 1];

        for (int i = 0; i < NUM_RECEIVER_ESMES + 1; i++) {
            String clusterName = "Receivers";
            if (i == 0) {
                clusterName = "Sender";
            }
            String strAddrRange = addressRanges[i];

            esmes[i] = smppManagementProxy.createEsme("CLIENT", esmeNamePref + i, clusterName, systemId, password, localAddress,
                    localPort - i - 1, strAddrRange, 1, 4, 2, 0);
            smppManagementProxy.startEsme(esmeNamePref + i);
        }

        // to enable automatic expiration of requests, a second scheduled executor
        // is required which is what a monitor task will be executed with - this is
        // probably a thread pool that can be shared with between all client bootstraps
        monitorExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(16, new ThreadFactory() {
            private AtomicInteger sequence = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("SmppClientSessionWindowMonitorPool-" + sequence.getAndIncrement());
                return t;
            }
        });
    }

    @AfterClass
    public void tearDownClass() throws Exception {
        logger.info("tearDownClass");

        for (Esme esme : esmes) {
            smppManagementProxy.stopEsme(esme.getName());
            smppManagementProxy.destroyEsme(esme.getName());
        }

        monitorExecutor.shutdownNow();
    }

    @BeforeMethod
    public void beforeTest() throws Exception {

    }

    @AfterMethod
    public void afterTest() throws Exception {
        if (servers != null) {
            for (int i = 0; i < NUM_RECEIVER_ESMES + 1; i++) {
                if (servers[i] != null && servers[i].smppServer.isStarted()) {
                    servers[i].stop();
                }
            }
        }
    }

    private void startServersClients(AtomicInteger reqReceived) throws Exception {

        servers = new Server[NUM_RECEIVER_ESMES + 1];

        ActivityTimeoutSmppSessionHandler[] handlers = new ActivityTimeoutSmppSessionHandler[NUM_RECEIVER_ESMES + 1];
        for (int i = 0; i < NUM_RECEIVER_ESMES + 1; i++) {
            handlers[i] = new ActivityTimeoutSmppSessionHandler(monitorExecutor, 2, reqReceived);
            servers[i] = new Server(handlers[i], localPort - i - 1);
            servers[i].init();
        }

        logger.info("Waiting for SMSC to initiate connections");
        Thread.sleep(32 * 1000);
        logger.info("Test preparation completed");

        for (int i = 0; i < NUM_RECEIVER_ESMES + 1; i++) {
            handlers[i].setSession(servers[i].getServerHandler().getSession());
        }
    }

    @Test
    public void test1() throws Exception {

        AtomicInteger reqReceived = new AtomicInteger(0);

        try {
            startServersClients(reqReceived);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            for (int i = 0; i < 6; i++)
                servers[0].sendRequestPdu(message + "server-server", senderNumber, receiverNumbers[0]);
        } catch (NullPointerException npe) {
            System.out.println("NullPointerException: " + npe.getMessage());
            npe.printStackTrace();
        }

        Thread.sleep(22 * 1000);

        for (int i = 0; i < esmes.length; i++) {
            esmes[i] = smppManagementProxy.getEsmeByName(esmes[i].getName());
        }
        logger.info("ESME 0 OVERLOADED:" + esmes[0].getOverloaded());
        logger.info("ESME 1 OVERLOADED:" + esmes[1].getOverloaded());
        logger.info("ESME 2 OVERLOADED:" + esmes[2].getOverloaded());

        Boolean overloaded = esmes[1].getOverloaded() ^ esmes[2].getOverloaded() ^ esmes[0].getOverloaded();
        assertTrue(overloaded);
        logger.info("Waiting for response to be sent");
        Thread.sleep(20 * 1000);
        assertEquals(6, reqReceived.get());
    }

    @Test
    public void test2() throws Exception {

        AtomicInteger reqReceived = new AtomicInteger(0);

        try {
            startServersClients(reqReceived);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 16; i++)
                    servers[0].sendRequestPdu(message + "server-server", senderNumber, receiverNumbers[j]);
            }

        } catch (NullPointerException npe) {
            System.out.println("NullPointerException: " + npe.getMessage());
            npe.printStackTrace();
        }

        Thread.sleep(22 * 1000);

        for (int i = 0; i < 16; i++)
            servers[0].sendRequestPdu(message + "server-server", senderNumber, receiverNumbers[2]);

        for (int i = 0; i < esmes.length; i++) {
            esmes[i] = smppManagementProxy.getEsmeByName(esmes[i].getName());
        }
        logger.info("ESME 0 OVERLOADED:" + esmes[0].getOverloaded());
        logger.info("ESME 1 OVERLOADED:" + esmes[1].getOverloaded());
        logger.info("ESME 2 OVERLOADED:" + esmes[2].getOverloaded());

        Boolean overloaded = esmes[1].getOverloaded() && esmes[2].getOverloaded();
        assertTrue(overloaded);
        Thread.sleep(40 * 1000);
        assertEquals(32, reqReceived.get());
        logger.info("Waiting for response to be sent");
        Thread.sleep(60 * 1000);
        assertEquals(32, reqReceived.get());
        Thread.sleep(330 * 1000);
        assertEquals(48, reqReceived.get());
    }
}