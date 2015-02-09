/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance *
http://www.apache.org/licenses/LICENSE-2.0 *
Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License. */

package org.apache.streams.util.api.requests.backoff.impl;

import org.apache.streams.util.api.requests.backoff.AbstractBackOffStrategy;

/**
 * Exponential backk strategy.  Caluclated by baseBackOffTimeInSeconds raised the attempt-count power.
 */
public class ExponentialBackOffStrategy extends AbstractBackOffStrategy {


    /**
     * Unlimited use ExponentialBackOffStrategy
     * @param baseBackOffTimeInSeconds
     */
    public ExponentialBackOffStrategy(int baseBackOffTimeInSeconds) {
        this(baseBackOffTimeInSeconds, -1);
    }

    /**
     * Limited use ExponentialBackOffStrategy
     * @param baseBackOffTimeInSeconds
     * @param maxNumAttempts
     */
    public ExponentialBackOffStrategy(int baseBackOffTimeInSeconds, int maxNumAttempts) {
        super(baseBackOffTimeInSeconds, maxNumAttempts);
    }

    @Override
    protected long calculateBackOffTime(int attemptCount, long baseSleepTime) {
        return Math.round(Math.pow(baseSleepTime, attemptCount)) * 1000;
    }
}
