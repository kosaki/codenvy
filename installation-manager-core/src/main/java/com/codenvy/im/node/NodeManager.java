/*
 * CODENVY CONFIDENTIAL
 * __________________
 *
 *  [2012] - [2015] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.im.node;

import com.codenvy.im.agent.AgentException;
import com.codenvy.im.artifacts.CDECArtifact;
import com.codenvy.im.command.CheckInstalledVersionCommand;
import com.codenvy.im.command.Command;
import com.codenvy.im.command.CommandException;
import com.codenvy.im.command.CommandFactory;
import com.codenvy.im.command.MacroCommand;
import com.codenvy.im.config.Config;
import com.codenvy.im.config.ConfigUtil;
import com.codenvy.im.install.InstallOptions;
import com.codenvy.im.utils.Version;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.codenvy.im.command.CommandFactory.createLocalAgentBackupCommand;
import static com.codenvy.im.command.CommandFactory.createLocalAgentPropertyReplaceCommand;
import static com.codenvy.im.command.CommandFactory.createShellAgentBackupCommand;
import static com.codenvy.im.command.CommandFactory.createShellAgentCommand;
import static com.codenvy.im.command.SimpleCommand.createLocalAgentCommand;
import static com.codenvy.im.service.InstallationManagerConfig.readPuppetMasterNodeDns;
import static java.lang.String.format;

/** @author Dmytro Nochevnov */
@Singleton
public class NodeManager {
    private ConfigUtil   configUtil;
    private CDECArtifact cdecArtifact;

    @Inject
    public NodeManager(ConfigUtil configUtil, CDECArtifact cdecArtifact) throws IOException {
        this.configUtil = configUtil;
        this.cdecArtifact = cdecArtifact;
    }

    /**
     * @throws IllegalArgumentException if node type isn't supported, or if there is adding node in the list of additional nodes
     */
    public String add(NodeConfig node) throws IOException, IllegalArgumentException {
        Config config = getCodenvyConfig(configUtil);
        AdditionalNodesConfigUtil nodesConfigUtil = getNodesConfigUtil(config);

        // check if Codenvy is alive
        Version currentCodenvyVersion = cdecArtifact.getInstalledVersion();

        String property = nodesConfigUtil.getPropertyNameBy(node.getType());
        if (property == null) {
            throw new IllegalArgumentException("This type of node isn't supported");
        }

        validate(node);

        Command addNodeCommand = getAddNodeCommand(currentCodenvyVersion, property, nodesConfigUtil, node, config);
        return addNodeCommand.execute();
    }

    /**
     * @return commands to add node into puppet master config and wait until node becomes alive
     */
    protected Command getAddNodeCommand(Version currentCodenvyVersion, String property, AdditionalNodesConfigUtil nodesConfigUtil, NodeConfig node, Config config) throws NodeException {
        List<Command> commands = new ArrayList<>();

        String value = nodesConfigUtil.getValueWithNode(node);
        NodeConfig apiNode = NodeConfig.extractConfigFrom(config, NodeConfig.NodeType.API);

        try {
            // modify puppet master config
            String puppetMasterConfigFilePath = "/etc/puppet/" + Config.MULTI_SERVER_PROPERTIES;
            commands.add(createLocalAgentBackupCommand(puppetMasterConfigFilePath));
            commands.add(createLocalAgentPropertyReplaceCommand(puppetMasterConfigFilePath,
                                                                "$" + property,
                                                                value));

//            // force to apply master config through puppet agent on API server  // TODO [ndp] take into account possible concurrent applying of config and lock of puppet agent on API server
//            commands.add(createShellAgentCommand("sudo puppet agent -t",
//                                                 apiNode));

            // check if there is a puppet agent started on adding node
            if (!isPuppetAgentActive(node)) {
                String puppetMasterNodeDns = readPuppetMasterNodeDns();

                // install puppet agents on adding node
                commands.add(createShellAgentCommand("if [ \"`yum list installed | grep puppetlabs-release.noarch`\" == \"\" ]; "
                                                     + format("then sudo yum install %s -y", config.getValue(Config.PUPPET_RESOURCE_URL))
                                                     + "; fi",
                                                     node));
                commands.add(createShellAgentCommand(format("sudo yum install %s -y", config.getValue(Config.PUPPET_AGENT_VERSION)), node));

                commands.add(createShellAgentCommand("if [ ! -f /etc/systemd/system/multi-user.target.wants/puppet.service ]; then" +
                                                     " sudo ln -s '/usr/lib/systemd/system/puppet.service' '/etc/systemd/system/multi-user.target" +
                                                     ".wants/puppet.service'" +
                                                     "; fi",
                                                     node));
                commands.add(createShellAgentCommand("sudo systemctl enable puppet", node));

                // configure puppet agent
                commands.add(createShellAgentBackupCommand("/etc/puppet/puppet.conf", node));
                commands.add(createShellAgentCommand(format("sudo sed -i 's/\\[main\\]/\\[main\\]\\n" +
                                                            "  server = %s\\n" +
                                                            "  runinterval = 420\\n" +
                                                            "  configtimeout = 600\\n/g' /etc/puppet/puppet.conf",
                                                            puppetMasterNodeDns),
                                                     node));

                commands.add(createShellAgentCommand(format("sudo sed -i 's/\\[agent\\]/\\[agent\\]\\n" +
                                                            "  show_diff = true\\n" +
                                                            "  pluginsync = true\\n" +
                                                            "  report = true\\n" +
                                                            "  default_schedules = false\\n" +
                                                            "  certname = %s\\n/g' /etc/puppet/puppet.conf",
                                                            node.getHost()),
                                                     node));

                // configure puppet master to use new puppet agent - remove out-date agent's certificate
                commands.add(createLocalAgentCommand(format("sudo puppet cert clean %s", node.getHost())));

                // start puppet agent
                commands.add(createShellAgentCommand("sudo systemctl start puppet", node));

                // wait until server on additional node is installed
                commands.add(createShellAgentCommand("doneState=\"Installing\"; " +
                                                     "testFile=\"/home/codenvy/codenvy-tomcat/logs/catalina.out\"; " +
                                                     "while [ \"${doneState}\" != \"Installed\" ]; do " +
                                                     "    sleep 30; " +
                                                     "    if sudo test -f ${testFile}; then doneState=\"Installed\"; fi; " +
                                                     "done",
                                                     node));
            }

            // wait until there is a changed configuration on API server
            commands.add(createShellAgentCommand(format("testFile=\"/home/codenvy/codenvy-data/conf/general.properties\"; " +
                                                        "while true; do " +
                                                        "    if sudo grep \"%s$\" ${testFile}; then break; fi; " +
                                                        "    sleep 5; " +  // sleep 5 sec
                                                        "done; " +
                                                        "sleep 15; # delay to involve into start of rebooting api server", value),
                                                 apiNode));

            // wait until API server restarts
            commands.add(new CheckInstalledVersionCommand(cdecArtifact, currentCodenvyVersion));
        } catch (Exception e) {
            throw new NodeException(e.getMessage(), e);
        }

        return new MacroCommand(commands, "Add node commands");
    }

    /**
     * @throws IllegalArgumentException if node type isn't supported, or if there is no removing node in the list of additional nodes
     */
    public String remove(String dns) throws IOException, IllegalArgumentException {
        Config config = getCodenvyConfig(configUtil);
        AdditionalNodesConfigUtil nodesConfigUtil = getNodesConfigUtil(config);

        // check if Codenvy is alive
        Version currentCodenvyVersion = cdecArtifact.getInstalledVersion();

        NodeConfig.NodeType nodeType = nodesConfigUtil.recognizeNodeTypeBy(dns);
        if (nodeType == null) {
            throw new NodeException(format("Node '%s' is not found in Codenvy configuration among additional nodes", dns));
        }

        String property = nodesConfigUtil.getPropertyNameBy(nodeType);
        if (property == null) {
            throw new IllegalArgumentException(format("Node type '%s' isn't supported", nodeType));
        }

        Command command = getRemoveNodeCommand(new NodeConfig(nodeType, dns), config, nodesConfigUtil, currentCodenvyVersion, property);
        return command.execute();
    }

    protected Command getRemoveNodeCommand(NodeConfig node,
                                          Config config,
                                          AdditionalNodesConfigUtil nodesConfigUtil,
                                          Version currentCodenvyVersion,
                                          String property) throws NodeException {
        try {
            String value = nodesConfigUtil.getValueWithoutNode(node);
            String puppetMasterConfigFilePath = "/etc/puppet/" + Config.MULTI_SERVER_PROPERTIES;
            NodeConfig apiNode = NodeConfig.extractConfigFrom(config, NodeConfig.NodeType.API);

            return new MacroCommand(ImmutableList.of(
                // modify puppet master config
                createLocalAgentBackupCommand(puppetMasterConfigFilePath),
                createLocalAgentPropertyReplaceCommand(puppetMasterConfigFilePath,
                                                       "$" + property,
                                                       value),

//                // force to apply master config through puppet agent on API server  // TODO [ndp] take into account possible concurrent applying of config and lock of puppet agent on API server
//                createShellAgentCommand("sudo puppet agent -t",
//                                        apiNode),

                // wait until there node is removed from configuration on API server
                createShellAgentCommand(format("testFile=\"/home/codenvy/codenvy-data/conf/general.properties\"; " +
                                               "while true; do " +
                                               "    if ! sudo grep \"%s\" ${testFile}; then break; fi; " +
                                               "    sleep 5; " +  // sleep 5 sec
                                               "done; " +
                                               "sleep 15; # delay to involve into start of rebooting api server", node.getHost()),
                                        apiNode),

                // wait until API server restarts
                new CheckInstalledVersionCommand(cdecArtifact, currentCodenvyVersion)
            ), "Remove node commands");
        } catch (Exception e) {
            throw new NodeException(e.getMessage(), e);
        }
    }

    protected boolean isPuppetAgentActive(NodeConfig node) throws AgentException {
        Command getPuppetAgentStatusCommand = getShellAgentCommand("sudo service puppet status", node);

        String result = null;
        try {
            result = getPuppetAgentStatusCommand.execute();
        } catch (CommandException e) {
            return false;
        }

        return result != null
               && result.contains("Loaded: loaded")
               && result.contains("(running)");
    }

    protected void validate(NodeConfig node) throws NodeException {
        String testCommand = "sudo ls";
        try {
            Command nodeCommand = getShellAgentCommand(testCommand, node);
            nodeCommand.execute();
        } catch (AgentException | CommandException e) {
            throw new NodeException(e.getMessage(), e);
        }
    }

    /** for testing propose */
    protected Command getShellAgentCommand(String command, NodeConfig node) throws AgentException {
        return CommandFactory.createShellAgentCommand(command, node);
    }

    protected Config getCodenvyConfig(ConfigUtil configUtil) throws IOException {
        Map<String, String> properties = configUtil.loadInstalledCodenvyProperties(InstallOptions.InstallType.CODENVY_MULTI_SERVER);
        return new Config(properties);
    }

    /** for testing propose */
    protected AdditionalNodesConfigUtil getNodesConfigUtil(Config config) {
        return new AdditionalNodesConfigUtil(config);
    }
}
