/*
 * (C) Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *   ohun@live.cn (夜色)
 */

package com.mpush.bootstrap.job;

import com.mpush.api.service.Listener;
import com.mpush.bootstrap.BootException;
import com.mpush.zk.ZKClient;

/**
 * Created by yxx on 2016/5/14.
 *
 * @author ohun@live.cn
 */
public class ZKBoot extends BootJob {

    @Override
    public void run() {
        ZKClient.I.start(new Listener() {
            @Override
            public void onSuccess(Object... args) {
                next();
            }

            @Override
            public void onFailure(Throwable cause) {
                throw new BootException("init zk client failure", cause);
            }
        });
    }
}
