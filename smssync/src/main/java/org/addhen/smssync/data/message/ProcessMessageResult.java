/*
 * Copyright (c) 2010 - 2015 Ushahidi Inc
 * All rights reserved
 * Contact: team@ushahidi.com
 * Website: http://www.ushahidi.com
 * GNU Lesser General Public License Usage
 * This file may be used under the terms of the GNU Lesser
 * General Public License version 3 as published by the Free Software
 * Foundation and appearing in the file LICENSE.LGPL included in the
 * packaging of this file. Please review the following information to
 * ensure the GNU Lesser General Public License version 3 requirements
 * will be met: http://www.gnu.org/licenses/lgpl.html.
 *
 * If you have questions regarding the use of this file, please contact
 * Ushahidi developers at team@ushahidi.com.
 */

package org.addhen.smssync.data.message;

import com.google.gson.Gson;

import org.addhen.smssync.R;
import org.addhen.smssync.data.cache.FileManager;
import org.addhen.smssync.data.entity.Message;
import org.addhen.smssync.data.entity.MessageResult;
import org.addhen.smssync.data.entity.MessagesUUIDSResponse;
import org.addhen.smssync.data.entity.QueuedMessages;
import org.addhen.smssync.data.entity.SyncUrl;
import org.addhen.smssync.data.net.AppHttpClient;
import org.addhen.smssync.data.net.BaseHttpClient;
import org.addhen.smssync.data.repository.datasource.message.MessageDataSource;
import org.addhen.smssync.data.repository.datasource.webservice.WebServiceDataSource;
import org.addhen.smssync.data.util.JsonUtils;
import org.addhen.smssync.data.util.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.text.TextUtils;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Created by Kamil Kalfas(kkalfas@soldevelo.com) on 23.04.14.
 * <p/>
 * This class handling Message Results API
 * <p/>
 * POST ?task=sent queued_messages {@link #sendQueuedMessagesPOSTRequest(
 * org.addhen.smssync.data.entity.SyncUrl, org.addhen.smssync.data.entity.QueuedMessages)}
 * POST ?task=results message_results {@link #sendMessageResultPOSTRequest(
 * org.addhen.smssync.data.entity.SyncUrl, java.util.List)}
 * GET ?task=results {@link #sendMessageResultGETRequest(org.addhen.smssync.data.entity.SyncUrl)}
 */
@Singleton
public class ProcessMessageResult {

    protected static final String TAG = ProcessMessageResult.class.getSimpleName();

    private static final String MESSAGE_RESULT_JSON_KEY = "message_result";

    private static final String TASK_SENT_URL_PARAM = "?task=sent";

    private static final String TASK_RESULT_URL_PARAM = "?task=result";

    private Context mContext;

    private AppHttpClient mAppHttpClient;

    private FileManager mFileManager;

    private WebServiceDataSource mWebServiceDataSource;

    private MessageDataSource mMessageDataSource;

    @Inject
    public ProcessMessageResult(Context context, AppHttpClient appHttpClient,
            FileManager fileManager, WebServiceDataSource webServiceDataSource,
            MessageDataSource messageDataSource) {
        mContext = context;
        mAppHttpClient = appHttpClient;
        mFileManager = fileManager;
        mWebServiceDataSource = webServiceDataSource;
        mMessageDataSource = messageDataSource;
        mMessageDataSource = messageDataSource;
    }

    public void processMessageResult() {
        List<SyncUrl> syncUrlList = mWebServiceDataSource.get(SyncUrl.Status.ENABLED);
        for (SyncUrl syncUrl : syncUrlList) {
            MessagesUUIDSResponse response = sendMessageResultGETRequest(syncUrl);
            if ((response != null) && (response.isSuccess()) && (response.hasUUIDs())) {
                final List<MessageResult> messageResults = new ArrayList<>();
                for (String uuid : response.getUuids()) {
                    Message message = mMessageDataSource.fetchPendingByUuid(uuid);
                    if (message != null) {
                        MessageResult messageResult = new MessageResult();
                        messageResult.setMessageUUID(message.getMessageUuid());
                        messageResult.setSentResultMessage(message.getSentResultMessage());
                        messageResult.setDeliveryResultCode(message.getDeliveryResultCode());
                        messageResult.setSentTimeStamp(message.getMessageDate());
                        messageResult.setDeliveredTimeStamp(message.getDeliveredDate());
                        messageResult.setDeliveryResultCode(message.getDeliveryResultCode());
                        messageResults.add(messageResult);
                    }
                }
                if (messageResults.size() > 0) {
                    sendMessageResultPOSTRequest(syncUrl, messageResults);
                }
            }
        }
    }

    /**
     * This method is handling POST ?task=result message_result
     *
     * @param syncUrl url to web server
     * @param results list of message result data
     */
    private void sendMessageResultPOSTRequest(SyncUrl syncUrl, List<MessageResult> results) {
        String newEndPointURL = syncUrl.getUrl().concat(TASK_RESULT_URL_PARAM);

        final String urlSecret = syncUrl.getSecret();

        if (!TextUtils.isEmpty(urlSecret)) {
            String urlSecretEncoded = urlSecret;
            newEndPointURL = newEndPointURL.concat("&secret=");
            try {
                urlSecretEncoded = URLEncoder.encode(urlSecret, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                mFileManager.append(e.getLocalizedMessage());
            }
            newEndPointURL = newEndPointURL.concat(urlSecretEncoded);
        }

        try {
            RequestBody body = RequestBody
                    .create(AppHttpClient.JSON, createMessageResultJSON(results));
            mAppHttpClient.setUrl(newEndPointURL);
            mAppHttpClient.setMethod(BaseHttpClient.HttpMethod.POST);
            mAppHttpClient.setRequestBody(body);
            mAppHttpClient.execute();

            if (200 == mAppHttpClient.getResponse().code()) {
                mFileManager.append(mContext.getString(R.string.message_processed_success));
            }
        } catch (Exception e) {
            Logger.log(TAG, "sendMessageResult failed", e);
            mFileManager.append(mContext.getString(R.string.message_processed_failed) + " " + e.getMessage());
        }
    }

    /**
     * This method is handling POST ?task=sent queued_messages
     *
     * @param syncUrl url to web server
     * @return parsed server response whit information about request success or failure and list of
     * message uuids
     */
    public MessagesUUIDSResponse sendQueuedMessagesPOSTRequest(SyncUrl syncUrl,
            QueuedMessages messages) {
        MessagesUUIDSResponse response = null;
        if (null != messages && !messages.getQueuedMessages().isEmpty()) {
            String newEndPointURL = syncUrl.getUrl().concat(TASK_SENT_URL_PARAM);
            mAppHttpClient.setUrl(newEndPointURL);

            try {
                RequestBody body = RequestBody
                        .create(AppHttpClient.JSON, createQueuedMessagesJSON(messages));
                mAppHttpClient.setMethod(BaseHttpClient.HttpMethod.POST);
                mAppHttpClient.setRequestBody(body);
                mAppHttpClient.execute();

                Response resp = mAppHttpClient.getResponse();
                if (200 == resp.code()) {
                    mFileManager.append(mContext.getString(R.string.message_processed_success));
                    response = parseMessagesUUIDSResponse(mAppHttpClient);
                    response.setSuccess(true);
                    mFileManager.append(mContext.getString(R.string.message_processed_success));
                } else {
                    response = new MessagesUUIDSResponse(resp.code());
                    mFileManager.append(mContext.getString(R.string.queued_messages_request_status,
                            resp.code(), resp.toString()));
                }
            } catch (Exception e) {
                Logger.log(TAG, "sendQueuedMessages failed", e);
                mFileManager.append(mContext.getString(R.string.message_processed_failed) + " " + e.getMessage());
                response = new MessagesUUIDSResponse(-1);
            }
        }
        return response;
    }

    /**
     * This method for handling GET ?task=result
     *
     * @param syncUrl url to web server
     * @return MessagesUUIDSResponse parsed server response whit information about request success
     * or failure and list of message uuids
     */
    public MessagesUUIDSResponse sendMessageResultGETRequest(SyncUrl syncUrl) {
        MessagesUUIDSResponse response;
        String newEndPointURL = syncUrl.getUrl().concat(TASK_RESULT_URL_PARAM);

        final String urlSecret = syncUrl.getSecret();

        if (!TextUtils.isEmpty(urlSecret)) {
            String urlSecretEncoded = urlSecret;
            newEndPointURL = newEndPointURL.concat("&secret=");
            try {
                urlSecretEncoded = URLEncoder.encode(urlSecret, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                mFileManager.append(e.getLocalizedMessage());
            }
            newEndPointURL = newEndPointURL.concat(urlSecretEncoded);
        }

        mAppHttpClient.setUrl(newEndPointURL);
        try {
            mAppHttpClient.setMethod(BaseHttpClient.HttpMethod.GET);
            mAppHttpClient.execute();

            Response resp = mAppHttpClient.getResponse();
            if (200 == resp.code()) {
                response = parseMessagesUUIDSResponse(mAppHttpClient);
                response.setSuccess(true);
            } else {
                response = new MessagesUUIDSResponse(resp.code());
                mFileManager.append(mContext.getString(R.string.messages_result_request_status,
                        resp.code(), resp.toString()));
            }
        } catch (Exception e) {
            Logger.log(TAG, "sendMessageResult failed", e);
            mFileManager.append(mContext.getString(R.string.message_processed_failed)
                    + " " + e.getMessage());
            response = new MessagesUUIDSResponse(-1);
        }
        return response;
    }

    private String createMessageResultJSON(List<MessageResult> messageResults)
            throws JSONException {
        JSONObject messageResultsObject = new JSONObject();
        messageResultsObject.put(MESSAGE_RESULT_JSON_KEY, JsonUtils.objToJson(messageResults));
        return messageResultsObject.toString();
    }

    private String createQueuedMessagesJSON(QueuedMessages queuedMessages) throws JSONException {
        return JsonUtils.objToJson(queuedMessages);
    }

    private MessagesUUIDSResponse parseMessagesUUIDSResponse(AppHttpClient client) {
        MessagesUUIDSResponse response;
        try {
            final Gson gson = new Gson();
            final int code = client.getResponse().code();
            response = gson.fromJson(client.getResponse().body().charStream(),
                    MessagesUUIDSResponse.class);
            response.setStatusCode(code);
        } catch (Exception e) {
            Logger.log(TAG, "parseMessages failed", e);
            response = new MessagesUUIDSResponse(-1);
            mFileManager.append(mContext.getString(R.string.message_processed_json_failed)
                    + " " + e.getMessage());
        }
        return response;
    }
}
