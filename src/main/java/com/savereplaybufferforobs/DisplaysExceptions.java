package com.savereplaybufferforobs;

public interface DisplaysExceptions {
    public void setObsException(ObsException exception);

    public void clearObsException();

    /** Clears the displayed exception only if it is this one. */
    void clearObsException(ObsException exception);
}
