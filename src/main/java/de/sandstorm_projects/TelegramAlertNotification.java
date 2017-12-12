package de.sandstorm_projects;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.alarms.callbacks.AlarmCallback;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackConfigurationException;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackException;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationException;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.configuration.fields.TextField.Attribute;
import org.graylog2.plugin.streams.Stream;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

public class TelegramAlertNotification implements AlarmCallback {
	private static final String CHAT = "chat";
	private static final String MESSAGE = "message";
	private static final String TOKEN = "token";
	private static final String WEBINTERFACE_URL = "webinterface_url";
	private static final String LOG_FILE = "log_file";
	
    private Configuration cfg;
    private Logger logger;
    private TelegramLongPollingBot bot;
    private long chatId;
    private String msgTemplate;
    private String webinterfaceUrl;

    @Override
    public void initialize(Configuration cfg) throws AlarmCallbackConfigurationException {
        this.cfg = cfg;
        logger = Logger.getLogger("TelegramAlert");
        String logPath = cfg.getString(LOG_FILE);
        
        if (!logPath.isEmpty()) {
	        try {
	        	FileHandler handler = new FileHandler(logPath, true);
	            logger.addHandler(handler);
	            handler.setFormatter(new SimpleFormatter());
	        } catch (IOException | SecurityException ex) {
	            ex.printStackTrace();
	        }
        }
        
        ApiContextInitializer.init();
        
        TelegramBotsApi botApi = new TelegramBotsApi();
    	bot = new TelegramAlertBot(cfg.getString(TOKEN));
        chatId = Long.parseLong(cfg.getString(CHAT));
        msgTemplate = cfg.getString(MESSAGE);
        webinterfaceUrl = cfg.getString(WEBINTERFACE_URL);
        
        if (!webinterfaceUrl.endsWith("/")) {
        	webinterfaceUrl = webinterfaceUrl + "/";
        }
        
        try {
        	botApi.registerBot(bot);
        } catch (TelegramApiException e) {
            logger.warning(e.getMessage());
        }
        
        logger.info("Alert was initialized successfully");
    }

    @Override
    public void call(Stream stream, AlertCondition.CheckResult checkResult) throws AlarmCallbackException {
    	try {
        	bot.execute(new SendMessage()
        			.setChatId(chatId)
        			.setParseMode("Markdown")
                    .setText(createRequestMsg(stream, checkResult)
            ));
        } catch (TelegramApiException e) {
            logger.warning(e.getMessage());
        }
    }


    private String createRequestMsg(Stream stream, AlertCondition.CheckResult checkResult) {
		String msg = msgTemplate;

		msg = msg.replaceAll("(?i)%streamTitle%", stream.getTitle());
		msg = msg.replaceAll("(?i)%streamDescription%", stream.getDescription());
		msg = msg.replaceAll("(?i)%streamUrl%", buildStreamLink(stream));
		msg = msg.replaceAll("(?i)%alertDescription%", checkResult.getTriggeredCondition().getDescription());
		
		StringBuilder backlog = new StringBuilder("```\n");
		getAlarmBacklog(checkResult).forEach(item -> {
			backlog.append(item);
			backlog.append("\n");
		});
		backlog.append("```");
		msg = msg.replaceAll("(?i)%backlog%", backlog.toString());
		
		return msg;
    }

    protected List<String> getAlarmBacklog(AlertCondition.CheckResult checkResult) {
        AlertCondition alertCondition = checkResult.getTriggeredCondition();
        List<MessageSummary> matchingMessages = checkResult.getMatchingMessages();

        int effectiveBacklogSize = Math.min(alertCondition.getBacklog(), matchingMessages.size());

        if (effectiveBacklogSize == 0) {
            return Collections.emptyList();
        }

        List<MessageSummary> backlogSummaries = matchingMessages.subList(0, effectiveBacklogSize);
        List<String> backlog = new ArrayList<>(effectiveBacklogSize);

        for (MessageSummary messageSummary : backlogSummaries) {
            backlog.add(messageSummary.getMessage());
        }

        return backlog;
    }

    protected String buildStreamLink(Stream stream) {
        return webinterfaceUrl + "streams/" + stream.getId() + "/messages?q=%2A&rangetype=relative&relative=3600";
    }

    protected String buildMessageLink(String index, String id) {
        return webinterfaceUrl + "messages/" + index + "/" + id;
    }

    @Override
    public ConfigurationRequest getRequestedConfiguration() {
        final ConfigurationRequest cfgRequest = new ConfigurationRequest();
        cfgRequest.addField(new TextField(
        		MESSAGE, "Message",
        		"[%streamTitle%](%streamUrl%):\n%backlog%",
        		"Message that will be sent, Placeholders: %streamTitle%, %streamDescription%, %streamUrl%, %alertDescription%, %backlog%",
        		ConfigurationField.Optional.NOT_OPTIONAL,
        		Attribute.TEXTAREA
        ));
        cfgRequest.addField(new TextField(
        		CHAT, "Chat ID", "",
        		"ID of the chat that messages should be sent to",
        		ConfigurationField.Optional.NOT_OPTIONAL
        ));
        cfgRequest.addField(new TextField(
        		TOKEN, "Bot Token", "",
        		"HTTP API Token you get from @BotFather",
        		ConfigurationField.Optional.NOT_OPTIONAL,
        		Attribute.IS_PASSWORD
        ));

        cfgRequest.addField(new TextField(
        		WEBINTERFACE_URL, "Graylog URL", "",
                "URL to your Graylog web interface. Used to build links in alarm notification.",
                ConfigurationField.Optional.NOT_OPTIONAL)
        );
        cfgRequest.addField(new TextField(
        		LOG_FILE, "File log",
        		"/tmp/telegramAlert.log",
        		"File path for debug logging",
                ConfigurationField.Optional.OPTIONAL
        ));
        return cfgRequest;
    }

    @Override
    public String getName() {
        return "Telegram Alert Notification";
    }

    @Override
    public Map<String, Object> getAttributes() {
        return cfg.getSource();
    }

    @Override
    public void checkConfiguration() throws ConfigurationException {
        if (!cfg.stringIsSet(MESSAGE)) {
            throw new ConfigurationException("Message is not set.");
        }

        if (!cfg.stringIsSet(CHAT)) {
            throw new ConfigurationException("Chat ID is not set.");
        } else if (Long.getLong(cfg.getString(CHAT)) != null) {
            throw new ConfigurationException("Invalid Chat ID");
        }
        
        if (!cfg.stringIsSet(TOKEN)) {
            throw new ConfigurationException("Token is not set.");
        }
        
        if (!cfg.stringIsSet(WEBINTERFACE_URL)) {
            throw new ConfigurationException("Graylog URL is not set.");
        }
    }
}