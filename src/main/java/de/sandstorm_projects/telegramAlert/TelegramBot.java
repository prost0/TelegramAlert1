package de.sandstorm_projects.telegramAlert;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicNameValuePair;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackException;
import org.graylog2.plugin.configuration.Configuration;

import de.sandstorm_projects.telegramAlert.config.Config;

class TelegramBot {
    private static final String API = "https://api.telegram.org/bot%s/%s";

    private String token;
    private String chat;
    private Logger logger;
    private String parseMode;
    private String postMode;
    private String proxy;

    TelegramBot(Configuration config) {
        this.token = config.getString(Config.TOKEN);
        this.chat = config.getString(Config.CHAT);
        this.parseMode = config.getString(Config.PARSE_MODE);
        this.postMode = config.getString(Config.POST_MODE);
        this.proxy = config.getString(Config.PROXY);

        logger = Logger.getLogger("TelegramAlert");
    }

    void sendMessage(String msg) throws AlarmCallbackException {
        final CloseableHttpClient client;

        if (!proxy.isEmpty()) {
            String[] proxyArr = proxy.split(":");
            HttpHost proxy = new HttpHost(proxyArr[0], Integer.parseInt(proxyArr[1]));
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            client = HttpClients.custom()
                    .setRoutePlanner(routePlanner)
                    .build();
        } else {
            client = HttpClients.createDefault();
        }

        try {
            HttpPost request = new HttpPost(String.format(API, token, "sendMessage"));
            HttpEntity entity =
                    postMode.equals("url") ? createUrlEncodedFormEntity(msg) : createJsonStringEntity(msg);

            request.setEntity(entity);

            HttpResponse response = client.execute(request);
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                String body = new BasicResponseHandler().handleResponse(response);
                String error = String.format("API request was unsuccessful (%d): %s", status, body);
                logger.warning(error);
                throw new AlarmCallbackException(error);
            }
        } catch (IOException e) {
            String error = "API request failed: " + e.getMessage();
            logger.warning(error);
            e.printStackTrace();
            throw new AlarmCallbackException(error);
        }
    }

    private HttpEntity createUrlEncodedFormEntity(String msg) throws UnsupportedEncodingException {
        List<NameValuePair> params = new ArrayList<>(4);
        params.add(new BasicNameValuePair("chat_id", chat));
        params.add(new BasicNameValuePair("text", msg));
        params.add(new BasicNameValuePair("disable_web_page_preview", "true"));
        if (!parseMode.equals("text")) {
            params.add(new BasicNameValuePair("parse_mode", parseMode));
        }

        return new UrlEncodedFormEntity(params, "UTF-8");
    }

    private HttpEntity createJsonStringEntity(String msg) {
        StringBuilder params = new StringBuilder("{");
        params.append("\"chat_id\":\"").append(chat).append("\"");
        params.append(",\"text\":\"").append(msg).append("\"");
        params.append(",\"disable_web_page_preview\":\"true\"");
        if (!parseMode.equals("text")) {
            params.append(",\"parse_mode\":\"").append(parseMode).append("\"");
        }
        params.append("}");
        return new StringEntity(params.toString(), ContentType.APPLICATION_JSON);
    }
}
