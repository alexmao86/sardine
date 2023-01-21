package com.github.sardine;

import java.io.IOException;
import java.util.List;

public class MainTest {
    public static void main(String[] args) throws IOException {
        Sardine sardine = SardineFactory.begin();
        List<DavResource> resources = sardine.list("http://localhost:8888");
        for (DavResource res : resources)
        {
            System.out.println(res);
        }
    }
}
