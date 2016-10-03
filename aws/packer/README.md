# Amazon Machine Image for News Crawler

Prerequisites
-------------

* build the News Crawler
* install [packer](https://www.packer.io/docs/installation.html)

Build the AMI
-------------

The Amazon machine image for the news crawler is based on
the [Amazon Linux AMI, HVM (SSD), ebs-backed, 64-bit, us-east-1](https://aws.amazon.com/amazon-linux-ami/).

To build the image, run from the root directory of the News Crawler project:

```
packer build aws/packer/newscrawl-ami.json
```

Run an EC2 instance
-------------------

The news crawler AMI should be registered on your AWS account. Select it to launch an EC2 instance.