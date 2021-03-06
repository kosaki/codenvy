/*
 *  [2012] - [2017] Codenvy, S.A.
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
package com.codenvy.machine.authentication.shared.dto;

import org.eclipse.che.dto.shared.DTO;

/**
 * Representation of machine token, that needed for communication
 * between workspace master and workspace agents.
 *
 * @author Max Shaposhnik (mshaposhnik@codenvy.com)
 */
@DTO
public interface MachineTokenDto {

    String getUserId();

    void setUserId(String userId);

    MachineTokenDto withUserId(String userId);


    String getWorkspaceId();

    void setWorkspaceId(String workspaceId);

    MachineTokenDto withWorkspaceId(String workspaceId);


    String getMachineToken();

    void setMachineToken(String machineToken);

    MachineTokenDto withMachineToken(String machineToken);
}
