// Copyright (C) 2008 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.ssh;

import com.google.gerrit.client.rpc.Common;
import com.google.gerrit.server.GerritServer;
import com.google.gwtjsonrpc.server.XsrfException;
import com.google.gwtorm.client.OrmException;

import org.apache.sshd.SshServer;
import org.apache.sshd.common.Compression;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.compression.CompressionNone;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.SecurityUtils;
import org.apache.sshd.server.UserAuth;
import org.apache.sshd.server.auth.UserAuthPublicKey;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.util.Arrays;

/**
 * SSH daemon to communicate with Gerrit.
 * <p>
 * Use a Git URL such as <code>ssh://${email}@${host}:${port}/${path}</code>,
 * e.g. <code>ssh://sop@google.com@gerrit.com:8010/tools/gerrit.git</code> to
 * access the SSH daemon itself.
 * <p>
 * Versions of Git before 1.5.3 may require setting the username and port
 * properties in the user's <code>~/.ssh/config</code> file, and using a host
 * alias through a URL such as <code>gerrit-alias:/tools/gerrit.git:
 * <pre>
 * Host gerrit-alias
 *  User sop@google.com
 *  Hostname gerrit.com
 *  Port 8010
 * </pre>
 */
public class GerritSshDaemon {
  private static SshServer sshd;
  private static final Logger log = LoggerFactory.getLogger(GerritSshDaemon.class);

  public static synchronized void startSshd() throws OrmException,
      XsrfException,SocketException {
    final GerritServer srv = GerritServer.getInstance();
    final int myPort = Common.getGerritConfig().getSshdPort();
    sshd = SshServer.setUpDefaultServer();
    sshd.setPort(myPort);

    final File sitePath = srv.getSitePath();
    if (SecurityUtils.isBouncyCastleRegistered()) {
      sshd.setKeyPairProvider(new FileKeyPairProvider(new String[] {
          new File(sitePath, "ssh_host_rsa_key").getAbsolutePath(),
          new File(sitePath, "ssh_host_dsa_key").getAbsolutePath()}));
    } else {
      final SimpleGeneratorHostKeyProvider keyp;

      keyp = new SimpleGeneratorHostKeyProvider();
      keyp.setPath(new File(sitePath, "ssh_host_key").getAbsolutePath());
      sshd.setKeyPairProvider(keyp);
    }

    // Always disable transparent compression. The majority of our data
    // transfer is highly compressed Git pack files. We cannot make them
    // any smaller than they already are.
    //
    sshd.setCompressionFactories(Arrays
        .<NamedFactory<Compression>> asList(new CompressionNone.Factory()));

    sshd.setUserAuthFactories(Arrays
        .<NamedFactory<UserAuth>> asList(new UserAuthPublicKey.Factory()));
    sshd.setPublickeyAuthenticator(new DatabasePubKeyAuth());
    sshd.setCommandFactory(new GerritCommandFactory());
    sshd.setShellFactory(new NoShell());

    try {
      sshd.start();
      log.info("Started Gerrit SSHD on 0.0.0.0:" + myPort);
    } catch (IOException e) {
      log.error("Cannot start Gerrit SSHD on 0.0.0.0:" + myPort, e);
      sshd = null;
      final SocketException e2;
      e2 = new SocketException("Cannot start sshd on " + myPort);
      e2.initCause(e);
      throw e2;
    }
  }

  public static synchronized void stopSshd() {
    if (sshd != null) {
      try {
        sshd.stop();
        log.info("Stopped Gerrit SSHD on 0.0.0.0:" + sshd.getPort());
      } finally {
        sshd = null;
      }
    }
  }

  public static synchronized int getSshdPort() {
    return sshd != null ? sshd.getPort() : 0;
  }
}