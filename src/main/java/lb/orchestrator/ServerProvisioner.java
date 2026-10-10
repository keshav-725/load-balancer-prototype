package lb.orchestrator;

import lb.model.ServerInfo;

/** Abstraction over "how do we get a new machine" (EC2 in real life, an in-process server here). */
public interface ServerProvisioner {
    ServerInfo launch();

    void terminate(String serverId);
}
