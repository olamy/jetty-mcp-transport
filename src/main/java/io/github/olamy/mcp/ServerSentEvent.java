//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package io.github.olamy.mcp;

import java.time.Duration;

public class ServerSentEvent {
    private String id;

    private String event;

    private Duration retry;

    private String comment;

    private String data;

    public ServerSentEvent(String id, String event, Duration retry, String comment, String data) {
        this.id = id;
        this.event = event;
        this.retry = retry;
        this.comment = comment;
    }

    public ServerSentEvent() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Duration getRetry() {
        return retry;
    }

    public void setRetry(Duration retry) {
        this.retry = retry;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
