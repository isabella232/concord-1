/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */
import * as v from './validation';

it('handles invalid repository URLs', () => {
    expect(v.repository.url('ftp://olala/')).toBeDefined();
    expect(v.repository.url('ssh://git@github.com:takari/bpm.git')).toBeDefined();
});

it('handles valid repository URLs', () => {
    expect(v.repository.url('git@github.com:takari/bpm.git')).toBeUndefined();
});