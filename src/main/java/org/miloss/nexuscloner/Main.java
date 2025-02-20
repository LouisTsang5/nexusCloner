/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.miloss.nexuscloner;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 *
 * @author alex.oree
 */
public class Main {

    static class Container {

        public Container() {
        }

        String url;
        String destinationPath;
        int size = -1;
        Exception ex;

        private Container(String link, String absolutePath) {
            url = link;
            destinationPath = absolutePath;
        }
    }

    static class Wrapper {

        public Wrapper() {
            data = new ArrayList<>();
        }

        public ArrayList<Container> data;
    }

    static class DownloadWorker implements Runnable {

        @Override
        public void run() {
            while (running && !filesToDownload.isEmpty()) {
                Container remove = filesToDownload.remove();
                if (remove != null) {
                    try {
                        File f = new File(remove.destinationPath);
                        if (f.exists() && f.length() > 0) {
                            //skip it
                        } else {
                            System.out.println("[" + filesToDownload.size() + "] downloading " + remove.url + " to " + remove.destinationPath);

                            f.getParentFile().mkdirs();

                            CloseableHttpClient client = HttpClients.createDefault();
                            HttpGet get = new HttpGet(remove.url);
                            if (cookie != null) {
                                get.addHeader("Cookie", cookie);
                            }
                            if (auth != null) {
                                get.addHeader("Authorization", "Basic " + auth);
                            }

                            CloseableHttpResponse execute = client.execute(get);
                            HttpEntity entity = execute.getEntity();
                            System.out.println(execute.getStatusLine().getStatusCode() + ": " + execute.getStatusLine().getReasonPhrase());
                            if (execute.getStatusLine().getStatusCode() != 200) {
                                throw new SecurityException(execute.getStatusLine().getStatusCode() + ": " + execute.getStatusLine().getReasonPhrase());
                            }
                            FileOutputStream os = new FileOutputStream(f);
                            InputStream is = entity.getContent();
                            byte[] buf = new byte[8192];
                            int bytesread = 0, bytesBuffered = 0;
                            while ((bytesread = is.read(buf)) > -1) {
                                os.write(buf, 0, bytesread);
                                bytesBuffered += bytesread;
                                if (bytesBuffered > 1024 * 1024) { //flush after 1MB
                                    bytesBuffered = 0;
                                    os.flush();
                                }
                            }
                            os.flush();
                            os.close();
                            is.close();
                            //FileUtils.copyInputStreamToFile(entity.getContent(), f);
                            get.releaseConnection();
                        }
                    } catch (SecurityException ex) {
                        System.err.println("Download error!" + remove.url);
                        ex.printStackTrace();
                        remove.ex = ex;
                        failures.add(remove);
                        return; //no need to continue download attempts
                    } catch (Exception ex) {
                        System.err.println("Download error!" + remove.url);
                        ex.printStackTrace();
                        remove.ex = ex;
                        failures.add(remove);
                    }
                }
            }
            System.out.println(Thread.currentThread().getId() + " thread terminated");
        }

    }

    static Wrapper ReadIndex(File indexFile, File outputFolder) throws FileNotFoundException {
        Wrapper wrapper = new Wrapper();
        if(indexFile.exists() && !indexFile.isDirectory()) {
            Scanner s = new Scanner(indexFile);
            while (s.hasNext()) {
                String url = s.nextLine();
                String relativePath = url.replace(sourceUrl, "");
                String AbsolutePath = outputFolder.getAbsolutePath() + File.separator + relativePath;
                wrapper.data.add(new Container(url, AbsolutePath));
            }
            s.close();
        }
        return wrapper;
    }

    static boolean running = true;
    static ArrayList<Container> failures = new ArrayList<>();

    static String sourceUrl = "";
    static String auth = null;
    static String cookie = null;
    static ConcurrentLinkedQueue<Container> filesToDownload = new ConcurrentLinkedQueue<>();
    static ArrayList<String> progress = new ArrayList<>();
    static String lastProgress = "";
    static boolean isRetrace = false;
    static FileWriter fw;

    public static void main(String[] args) throws Exception {

        final long now = System.currentTimeMillis();
        Options opts = new Options();
        opts.addOption("url", true, "root url of the repository you want to clone");

        opts.addOption("delay", true, "wait time between downloads (throttling)");
        opts.addOption("index", false, "build the index, must run first");
        opts.addOption("download", false, "download");
        opts.addOption("cookie", false, "prompt for cookie token");
        opts.addOption("username", true, "");
        opts.addOption("password", true, "prompt for password");
        opts.addOption("output", true, "output folder, default is ./output/");
        opts.addOption("threads", true, "threads for concurrent downloads");
        opts.addOption("help", false, "help");

        CommandLineParser parser = new DefaultParser();
        CommandLine parse = parser.parse(opts, args);

        //read last progress
        File progressFile = new File("progress.bin");
        if(progressFile.exists() && !progressFile.isDirectory()) {
            Scanner s = new Scanner(progressFile);
            while (s.hasNext()) {
                progress.add(s.next());
            }
            s.close();
        }
        //get last progress
        if (progress.size() > 0) {
            lastProgress = progress.get(progress.size() - 1);
            isRetrace = true;
        }

        //Clear last progress
        progress.clear();

        initSsl();
        if (parse.hasOption("help") 
                || !parse.hasOption("url") 
                || (!parse.hasOption("index") && !parse.hasOption("download") )){
            HelpFormatter f = new HelpFormatter();
            f.printHelp("java -jar nexusCloner-{VERSION}-jar-with-dependencies.jar {options}", opts);
            return;
        }
        
        String url = parse.getOptionValue("url");
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        if (parse.hasOption("username") && parse.hasOption("password")) {
            System.out.println("Password: ");

            String password = new String(System.console().readPassword());
            auth = new String(Base64.encodeBase64(
                    (parse.getOptionValue("username") + ":" + password).getBytes()
            ));
        }
        if (parse.hasOption("cookie")) {
            System.out.println("Cookie: ");

            String password = new String(System.console().readPassword());
            cookie = password;
            System.out.println("using cookie " + cookie);
        }
        sourceUrl = url;
        File outputFolder;
        if (parse.hasOption("output")) {
            outputFolder= new File(parse.getOptionValue("output"));
        } else 
            outputFolder= new File("./output");
        outputFolder.mkdirs();
        Kryo kryo = new Kryo();
        if (parse.hasOption("index")) {
            Exception exception = null;
            try {
                process(url, outputFolder);
            } catch (Exception ex) {
                exception = ex;
            }

            //store the index
            Iterator<Container> iterator = filesToDownload.iterator();
            //ArrayList<Container> data = new ArrayList<>();
            ArrayList<String> urls = new ArrayList<>();
//            while (iterator.hasNext()) {
//                data.add(iterator.next());
//            }
            while (iterator.hasNext()) {
                urls.add(iterator.next().url);
            }

            Wrapper d = new Wrapper();
            //d.data = data;
            // ...
            FileWriter fw = new FileWriter("index.bin", true);
            for (String urlString : urls) {
                fw.write(urlString + System.lineSeparator());
            }
            fw.close();

            FileWriter fw1 = new FileWriter("progress.bin", true);
            for (String progressUrl : progress) {
                fw1.write(progressUrl + System.lineSeparator());
            }
            fw1.close();
//            Output output = new Output(new FileOutputStream("index.bin"));
//
//            //kryo.writeObject(output, d);
//            kryo.writeObject(output, urls);
//            output.close();
            if (exception != null) {
                throw exception;
            }
        }
        if (parse.hasOption("download")) {
            if (filesToDownload.isEmpty()) {
                File f = new File("index.bin");
                if (!f.exists()) {
                    System.out.println("index.bin is missing, run with the -index option first.");
                    return;
                }
                //Input input = new Input(new FileInputStream(f));
                //Wrapper someObject = kryo.readObject(input, Wrapper.class);
                Wrapper someObject = ReadIndex(f, outputFolder);
                //input.close();
                filesToDownload.addAll(someObject.data);
            }
            System.out.println(filesToDownload.size() + " files to download");
            System.out.println(filesToDownload.size() + " files to download");
            System.out.println(filesToDownload.size() + " files to download");
            int threads = 4;
            if (parse.hasOption("threads")) {
                threads = Integer.parseInt(parse.getOptionValue("threads"));
            }
            for (int i = 0; i < threads; i++) {
                DownloadWorker dw = new DownloadWorker();
                new Thread(dw).start();
            }

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("processed in " + (System.currentTimeMillis() - now) + "ms");
                    System.out.println("Download failures: " + failures.size());
                    for (int i = 0; i < failures.size(); i++) {
                        System.out.println(failures.get(i).url + ": " + failures.get(i).ex.getMessage());
                    }
                }
            }));
            System.out.println("PRESS ENTER TO STOP THE DOWNLOADS AFTER THE CURRENT FILES END");
            System.console().readLine();
            running = false;
        }

        System.out.println("processed in " + (System.currentTimeMillis() - now) + "ms");
    }

    private static boolean isDirectory(String link) {
        return (link.endsWith("/"));
    }

    private static void process(String url, File outputFolder) throws Exception {
        outputFolder.mkdirs();
        System.out.println("processing directory " + url);
        Document doc;
        if (auth != null) {
            doc = Jsoup.connect(url).header("Authorization", "Basic " + auth).get();
        } else if (cookie != null) {
            doc = Jsoup.connect(url).header("Cookie", cookie).get();
        } else {
            doc = Jsoup.connect(url).get();
        }



        for (Element e : doc.select("a")) {
            //skip the directory if the current directory is not the last directory
            if (isRetrace)
                if (!Objects.equals(url, sourceUrl))
                    if (!lastProgress.contains(url))
                        continue;

            //end the retrace if the current directory is the last directory
            if (Objects.equals(url, lastProgress))
                isRetrace = false;

            String link = url + e.attr("href");
            if (!ignore(e.attr("href"))) {
                if (isDirectory(e.attr("href"))) {
                    process(link, outputFolder);
                } else {
                    //download the file
                    URL urlFile = new URL(link);
                    String relativePath = link.replace(sourceUrl, "");
                    File outputFile = new File(outputFolder.getAbsolutePath() + File.separator + relativePath);
                    progress.add(url);
                    filesToDownload.add(new Container(e.attr("href"), outputFile.getAbsolutePath()));
                    //FileUtils.copyURLToFile(urlFile, outputFile );
                }
                //System.out.println(link);
            }
        }

    }

    private static boolean ignore(String link) {
        if (link.endsWith("../")) {
            return true;
        }
        return false;
    }

    private List<String> getDirectoryListing(String nexusUrl) throws Exception {
        List<String> ret = new ArrayList<>();

        Document doc = Jsoup.connect(nexusUrl).get();
        for (Element file : doc.select("td a")) {
            System.out.println(file.attr("href"));
            if (!file.attr("href").equalsIgnoreCase("../")) {
                ret.add(file.attr("href"));
            }

        }

        return ret;
    }

    private static void initSsl() throws Exception {
        TrustManager[] trustall = new TrustManager[]{new X509TrustManager() {

            @Override
            public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                System.out.println("Trust no one");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                System.out.println("Trust no one");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                System.out.println("Trust no one");
                return null;
            }
        }};
        SSLContext sc = SSLContext.getInstance("SSL");

        sc.init(null, trustall, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    }
}
